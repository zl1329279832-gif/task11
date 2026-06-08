# 门诊预约后端系统

基于 Java 17 + Spring Boot 3.2 + MyBatis-Plus + MySQL + Redis 的门诊预约闭环系统。

## 核心功能

| 功能模块 | 说明 |
|---------|------|
| 科室/医生管理 | CRUD，状态管理 |
| 排班模板 | 按周模板自动生成日排班和号源 |
| 节假日停诊 | 批量停诊，自动释放号源 |
| 临时加号 | 在已有排班上增加号源 |
| 预约挂号 | 分布式锁 + 乐观锁双重并发控制 |
| 改签 | 原子释放旧号源 + 预约新号源 |
| 取消 | 释放号源 + 自动触发候补补位 |
| 签到/过号 | 状态流转 + 定时自动过号 |
| 候补队列 | 加入/取消/自动补位/过期清理 |
| 医生停诊 | 批量迁移到同科室其他医生 或 批量取消 |
| 审计日志 | 全链路操作记录 |

## 技术栈

- **Java 17** + **Spring Boot 3.2.5**
- **MyBatis-Plus 3.5.6** (ORM)
- **MySQL 8.0** (主数据库)
- **Redis 7** + **Redisson 3.27** (分布式锁)
- **Spring Scheduling** (定时任务)
- **Docker Compose** (开发环境)

## 快速启动

### 1. 启动基础设施

```bash
docker-compose up -d
```

这将启动:
- MySQL 8.0 (端口 3306, 密码 root123)
- Redis 7 (端口 6379)

### 2. 初始化数据库

```bash
# 连接MySQL并创建数据库
mysql -h localhost -u root -proot123 -e "CREATE DATABASE IF NOT EXISTS clinic CHARACTER SET utf8mb4;"

# 执行schema
mysql -h localhost -u root -proot123 clinic < src/main/resources/schema.sql
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

应用启动后访问 `http://localhost:8080`

### 4. 运行测试

```bash
# 确保MySQL和Redis已启动，并创建测试数据库
mysql -h localhost -u root -proot123 -e "CREATE DATABASE IF NOT EXISTS clinic_test CHARACTER SET utf8mb4;"
mysql -h localhost -u root -proot123 clinic_test < src/test/resources/schema-test.sql

# 运行全部测试
mvn test -Dspring.profiles.active=test

# 运行单个测试类
mvn test -Dspring.profiles.active=test -Dtest=ConcurrencyBookingTest
mvn test -Dspring.profiles.active=test -Dtest=SuspensionMigrationTest
mvn test -Dspring.profiles.active=test -Dtest=WaitlistBackfillTest
```

## 数据库设计

```
┌──────────────┐    ┌──────────────┐    ┌───────────────────┐
│  department  │───▶│   doctor     │───▶│ schedule_template │
│  (科室)      │    │   (医生)     │    │  (排班模板)        │
└──────────────┘    └──────┬───────┘    └───────────────────┘
                           │
                    ┌──────▼───────┐    ┌───────────────────┐
                    │doctor_schedule│───▶│  schedule_slot    │
                    │ (日排班)      │    │  (号源)           │
                    └──────────────┘    └───────┬───────────┘
                                                │
                    ┌──────────────┐    ┌───────▼───────────┐
                    │  waitlist    │◀───│  appointment      │
                    │ (候补队列)    │    │  (预约)           │
                    └──────────────┘    └───────────────────┘

┌──────────────┐  ┌──────────────────┐  ┌───────────────────┐
│   holiday    │  │doctor_suspension │  │   audit_log       │
│ (节假日停诊)  │  │ (医生停诊)       │  │  (审计日志)       │
└──────────────┘  └──────────────────┘  └───────────────────┘
```

### 核心表说明

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| `department` | 科室 | code(唯一) |
| `doctor` | 医生 | employee_no, department_id |
| `schedule_template` | 周排班模板 | day_of_week, time_period, total_slots |
| `doctor_schedule` | 日排班 | schedule_date, status(NORMAL/SUSPENDED/HOLIDAY) |
| `schedule_slot` | 号源 | status(状态机), version(乐观锁) |
| `appointment` | 预约 | status(状态机), appointment_no |
| `waitlist` | 候补队列 | priority, status(WAITING/FULFILLED/EXPIRED/CANCELLED) |
| `doctor_suspension` | 停诊记录 | migrate_type(CANCEL/MIGRATE) |
| `audit_log` | 审计日志 | operation, entity_type, detail(JSON) |

## 号源状态机

```
  AVAILABLE ──book──▶ BOOKED ──checkin──▶ CHECKED_IN (终态)
     │                  │
     │                  ├──cancel──▶ RELEASED ──backfill──▶ BOOKED
     │                  │
     │                  └──noshow──▶ MISSED (终态)
     │
     ├──expire──▶ EXPIRED (终态)
     │
     └──suspend──▶ RELEASED
```

## 预约状态机

```
  PENDING ──confirm──▶ CONFIRMED ──checkin──▶ CHECKED_IN (终态)
     │                    │
     │                    ├──cancel──▶ CANCELLED (终态)
     │                    │
     │                    └──reschedule──▶ RESCHEDULED (终态，产生新预约)
     │
     └──cancel──▶ CANCELLED (终态)

  CONFIRMED ──noshow──▶ MISSED (终态)
```

## 候补补位调度逻辑

```
取消预约/改签释放号源
        │
        ▼
  触发 triggerBackfill()
        │
        ├── 获取分布式锁 (lock:backfill:{doctorId}:{date})
        │
        ├── 查询可用号源 (status=AVAILABLE)
        │
        ├── 查询候补队列 (status=WAITING, ORDER BY priority, create_time)
        │
        ├── 遍历候补队列:
        │     ├── 取一个可用号源
        │     ├── 调用 AppointmentService.book() 创建预约
        │     ├── 更新候补状态为 FULFILLED
        │     └── 号源用完则停止
        │
        └── 释放锁

补偿机制:
  定时任务每15分钟扫描，补偿异步触发失败的情况
```

## 并发预约控制

采用 **分布式锁 + 乐观锁 (CAS) 双重保护**:

```
1. 外层: Redisson分布式锁 (lock:slot:{slotId})
   ├── 防止同一号源被并发操作
   └── 等待5秒，持有10秒

2. 内层: 号源version字段乐观锁
   ├── UPDATE schedule_slot SET status='BOOKED' WHERE id=? AND status='AVAILABLE' AND version=?
   └── 返回affected rows=0则说明被其他线程抢先
```

## 停诊迁移逻辑

```
发布停诊 (DoctorSuspensionService.suspend)
    │
    ├── 获取分布式锁 (lock:suspend:{doctorId})
    │
    ├── 查找受影响预约 (status IN PENDING, CONFIRMED)
    │
    ├── 模式判断:
    │   ├── CANCEL模式:
    │   │   └── 批量取消所有预约，释放号源
    │   │
    │   └── MIGRATE模式:
    │       ├── 查找同科室其他在职医生
    │       ├── 无替代医生 → 降级为取消
    │       └── 有替代医生:
    │           ├── 查找替代医生同日可用号源
    │           ├── CAS占用新号源
    │           ├── 原预约标记RESCHEDULED
    │           └── 创建新预约(指向替代医生)
    │
    ├── 批量释放号源
    ├── 更新排班状态为SUSPENDED
    └── 更新停诊记录为COMPLETED
```

## 核心API

### 基础数据
| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/departments` | 创建科室 |
| GET | `/api/departments` | 科室列表 |
| POST | `/api/doctors` | 创建医生 |
| GET | `/api/doctors?departmentId=` | 医生列表 |
| POST | `/api/holidays` | 创建节假日 |

### 排班管理
| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/schedules/generate` | 生成排班(从模板) |
| POST | `/api/schedules/extra-slot` | 临时加号 |
| GET | `/api/schedules/doctor/{id}?start=&end=` | 查询排班 |
| GET | `/api/schedules/slots/doctor/{id}/date/{date}` | 可用号源 |
| POST | `/api/schedules/holiday-suspend?date=&departmentId=` | 节假日停诊 |

### 预约管理
| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/appointments/book` | 预约挂号 |
| POST | `/api/appointments/cancel` | 取消预约 |
| POST | `/api/appointments/reschedule` | 改签 |
| POST | `/api/appointments/{id}/checkin` | 签到 |
| POST | `/api/appointments/{id}/missed` | 过号 |
| GET | `/api/appointments/{id}` | 查询预约 |
| GET | `/api/appointments/patient/{patientId}` | 患者预约列表 |

### 候补队列
| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/waitlist/join` | 加入候补 |
| POST | `/api/waitlist/{id}/cancel` | 取消候补 |
| GET | `/api/waitlist/patient/{patientId}` | 患者候补列表 |
| GET | `/api/waitlist/doctor/{id}/date/{date}` | 医生某日候补 |

### 停诊管理
| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/suspensions` | 发布停诊(自动处理预约) |
| GET | `/api/suspensions/{id}` | 查询停诊记录 |

## 定时任务

| 频率 | 任务 | 说明 |
|------|------|------|
| 每小时 | `expireSlots` | 过期号源清理 |
| 每30分钟 | `markMissedAppointments` | 未签到自动过号 |
| 每天1:00 | `expireWaitlists` | 候补过期清理 |
| 每15分钟 | `waitlistBackfillCompensation` | 候补补位补偿 |

## 测试用例

### ConcurrencyBookingTest (并发预约)
- 10个线程同时预约同一号源 → 仅1个成功
- 5个线程各约不同号源 → 全部成功

### SuspensionMigrationTest (停诊迁移)
- 迁移模式: 3个预约迁移到同科室医生B
- 取消模式: 3个预约直接取消
- 降级场景: 无替代医生时自动降级为取消

### WaitlistBackfillTest (候补补位)
- 取消预约 → 候补第1人自动补位
- 多次取消 → 按优先级顺序依次补位
- 重复候补 → 拒绝
- 手动触发补位 → 正常工作

## 项目结构

```
src/main/java/com/clinic/appointment/
├── ClinicApplication.java          # 启动类
├── config/
│   ├── RedisConfig.java           # Redisson配置
│   └── GlobalExceptionHandler.java # 全局异常处理
├── exception/
│   └── BusinessException.java     # 业务异常
├── domain/
│   ├── enums/                     # 状态枚举
│   ├── entity/                    # 实体类
│   └── dto/                       # 请求/响应DTO
├── mapper/                        # MyBatis Mapper
├── service/
│   ├── RedisLockService.java      # 分布式锁
│   ├── AuditService.java          # 审计日志
│   ├── ScheduleService.java       # 排班管理
│   ├── AppointmentService.java    # 预约核心
│   ├── WaitlistService.java       # 候补队列
│   ├── DoctorSuspensionService.java # 停诊处理
│   └── impl/                      # 实现类
├── controller/                    # REST接口
└── task/
    └── ScheduledTasks.java        # 定时任务
```
