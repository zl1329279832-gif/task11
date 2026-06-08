# 门诊预约后端系统

## 技术栈

- Java 17 / Spring Boot 3.2.5
- MyBatis 3.0.3
- MySQL 8.x (生产) / H2 (测试)
- Redis + Redisson (分布式锁)
- Spring Scheduling (定时任务)

## 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.x
- Redis 6.x+

## 快速启动

### 1. 创建数据库

```sql
CREATE DATABASE clinic_appointment DEFAULT CHARACTER SET utf8mb4;
```

导入表结构:

```bash
mysql -u root -p clinic_appointment < src/main/resources/schema.sql
```

### 2. 修改配置

编辑 `src/main/resources/application.yml`，设置 MySQL 和 Redis 连接信息:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/clinic_appointment?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379
```

### 3. 启动

```bash
mvn spring-boot:run
```

服务默认运行在 `http://localhost:8080`。

### 4. 运行测试

测试使用 H2 内存数据库 + Mock Redis，无需外部依赖:

```bash
mvn test
```

## 数据库设计

| 表名 | 用途 |
|------|------|
| department | 科室 |
| doctor | 医生 |
| schedule_template | 排班模板(周级别) |
| schedule | 排班实例(日级别) |
| slot | 号源(单个时间段) |
| appointment | 预约记录 |
| waitlist | 候补队列 |
| holiday | 节假日 |
| suspension | 停诊记录 |
| audit_log | 审计日志 |

## 号源状态机

```
AVAILABLE ──lock──> LOCKED ──confirm──> BOOKED ──checkIn──> CHECKED_IN ──complete──> COMPLETED
    ^                 |                   |                                 |
    |             timeout/fail          cancel                           pass
    |                 |                   |                                 |
    └───release───────┘                   |                                 v
    └─────────────release─────────────────┘                              PASSED
```

## 预约状态流转

```
BOOKED ──checkIn──> CHECKED_IN ──complete──> COMPLETED
  |                     |
  cancel              pass
  |                     |
  v                     v
CANCELLED            PASSED

BOOKED ──reschedule──> RESCHEDULED (原预约)
                       + 新 BOOKED (新预约)
```

## 核心业务闭环

### 并发预约控制

1. Redis 分布式锁 (`lock:slot:{slotId}`) 防止跨进程并发
2. 数据库乐观锁 (version 字段 CAS) 防止进程内并发
3. 双重保护确保同一号源不会被重复预约

### 候补补位流程

```
患者预约 -> 号满 -> 加入候补队列(按位置排序)
                         |
其他患者取消 -> 释放号源 -> 自动取队首候补 -> CAS锁定号源 -> 创建预约(source=WAITLIST)
                                              |
                                          候补状态: WAITING -> CONVERTED
```

### 停诊迁移流程

```
医生停诊请求
  |
  ├── action=CANCEL: 批量取消所有受影响预约，释放号源，取消候补
  |
  └── action=MIGRATE(targetDoctorId):
        ├── 查找目标医生同日排班的可用号源
        ├── 逐个迁移(创建新预约，原预约标记RESCHEDULED)
        └── 号源不足时，剩余预约自动取消
```

## API 接口

### 科室管理
| Method | Path | 说明 |
|--------|------|------|
| POST | /api/departments | 创建科室 |
| PUT | /api/departments | 更新科室 |
| GET | /api/departments/{id} | 查询科室 |
| GET | /api/departments | 查询所有科室 |

### 医生管理
| Method | Path | 说明 |
|--------|------|------|
| POST | /api/doctors | 创建医生 |
| PUT | /api/doctors | 更新医生 |
| GET | /api/doctors/{id} | 查询医生 |
| GET | /api/doctors | 查询所有医生 |
| GET | /api/doctors/department/{departmentId} | 按科室查询医生 |

### 排班管理
| Method | Path | 说明 |
|--------|------|------|
| POST | /api/schedules/generate | 根据模板批量生成排班 |
| GET | /api/schedules/{id} | 查询排班 |
| GET | /api/schedules?startDate=&endDate= | 按日期范围查询 |
| GET | /api/schedules/doctor/{doctorId}?startDate=&endDate= | 按医生和日期查询 |
| POST | /api/schedules/extra-slots | 临时加号 |

### 号源查询
| Method | Path | 说明 |
|--------|------|------|
| GET | /api/slots/schedule/{scheduleId} | 查询排班下所有号源 |
| GET | /api/slots/schedule/{scheduleId}/available | 查询可用号源 |
| GET | /api/slots/doctor/{doctorId}/date/{date} | 按医生和日期查询 |

### 预约管理
| Method | Path | 说明 |
|--------|------|------|
| POST | /api/appointments/book | 预约挂号 |
| POST | /api/appointments/cancel | 取消预约(触发候补补位) |
| POST | /api/appointments/reschedule | 改签(释放旧号+预约新号) |
| POST | /api/appointments/{id}/check-in | 签到 |
| POST | /api/appointments/{id}/pass | 过号 |
| POST | /api/appointments/{id}/complete | 完成就诊 |
| GET | /api/appointments/{id} | 查询预约 |
| GET | /api/appointments/patient/{patientId} | 查询患者预约 |

### 候补队列
| Method | Path | 说明 |
|--------|------|------|
| POST | /api/waitlist/join | 加入候补 |
| POST | /api/waitlist/{id}/cancel | 取消候补 |
| GET | /api/waitlist/patient/{patientId} | 查询患者候补 |
| GET | /api/waitlist/schedule/{scheduleId}?status=WAITING | 查询排班候补队列 |

### 停诊管理
| Method | Path | 说明 |
|--------|------|------|
| POST | /api/suspensions | 停诊(批量取消或迁移) |

### 节假日管理
| Method | Path | 说明 |
|--------|------|------|
| POST | /api/holidays | 添加节假日 |
| DELETE | /api/holidays/{id} | 删除节假日 |
| GET | /api/holidays | 查询所有节假日 |
| GET | /api/holidays/check?date= | 检查是否为节假日 |

### 审计日志
| Method | Path | 说明 |
|--------|------|------|
| GET | /api/audit/entity/{entityType}/{entityId} | 按实体查询审计记录 |
| GET | /api/audit/recent?limit=50 | 查询最近审计记录 |

## 定时任务

| 任务 | 周期 | 说明 |
|------|------|------|
| ScheduleGenerateScheduler | 每天 02:00 | 自动生成未来7天排班 |
| AppointmentAutoCloseScheduler | 每5分钟 | 自动取消超时未签到预约，触发候补补位 |
| WaitlistExpireScheduler | 每天 23:00 | 清理过期候补条目 |

## 测试覆盖

- **ConcurrentBookingTest** (3个): 单次预约、10线程并发预约同一号源(验证仅1个成功)、重复预约拒绝
- **SuspensionMigrationTest** (3个): 停诊全部取消、停诊全量迁移、停诊部分迁移(号源不足)
- **WaitlistBackfillTest** (5个): 加入候补、取消后自动补位、队列顺序(先进先出)、候补满额拒绝、取消候补

## 配置参数

在 `application.yml` 中可调整:

```yaml
appointment:
  max-waitlist-size: 10     # 每个排班的最大候补人数
  auto-cancel-minutes: 30   # 超时未签到自动取消(分钟)
  pass-recall-minutes: 15   # 过号召回时间(分钟)
  lock-wait-seconds: 5      # 分布式锁等待超时(秒)
```
