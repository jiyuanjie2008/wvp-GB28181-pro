# 案例：WVP 容器不断重启 —— `@EnableAsync` 触发 JDK 代理 + `@Scheduled` 方法不在接口中

> 创建时间：2026-05-10  
> 影响版本：commit `ee04798b9` 之后  
> 严重级别：P0（容器无法启动，服务完全不可用）  
> 修复方式：`VManageBootstrap.java` 上 `@EnableAsync` / `@EnableCaching` 显式声明 `proxyTargetClass = true`（commit 待补）

---

## 一、现象

`docker-polaris-wvp-1` 容器启动后约 7 秒退出，被 Docker 的 `restart: always` 策略不断拉起。

```text
NAMES                  STATUS        IMAGE
docker-polaris-wvp-1   Up 1 second   docker-polaris-wvp
```

`docker inspect` 关键字段：

```text
RestartCount = 40
ExitCode     = 0          ← 注意是 0，不是非零，说明是 JVM 优雅退出
OOMKilled    = false
```

---

## 二、定位过程

### 1. 看容器状态

`docker ps -a` + `docker inspect` 发现 `RestartCount` 已达 40，但退出码是 0，排除 OOM、SIGKILL、磁盘满等粗暴杀进程的场景，更像是应用自己主动退出。

### 2. 看应用日志

`docker logs --tail 200 docker-polaris-wvp-1` 找到决定性堆栈：

```text
2026-05-10 00:26:48.110 [main] ERROR --- org.springframework.boot.SpringApplication: 857 Application run failed

org.springframework.beans.factory.UnsatisfiedDependencyException:
  Error creating bean with name 'cloudRecordTimer':
    Unsatisfied dependency expressed through field 'mediaServerService':
  Error creating bean with name 'mediaServerServiceImpl':
    Unsatisfied dependency expressed through field 'inviteStreamService':
  Error creating bean with name 'inviteStreamServiceImpl':

Caused by: java.lang.IllegalStateException:
  Need to invoke method 'execute' declared on target class 'InviteStreamServiceImpl',
  but not found in any interface(s) of the exposed proxy type.
  Either pull the method up to an interface or switch to CGLIB proxies
  by enforcing proxy-target-class mode in your configuration.
```

错误链已经把根因写在脸上：

> 在代理类型上找不到 `execute` 方法。要么把方法提到接口里，要么强制使用 CGLIB 代理。

### 3. 验证代码事实

```java
// src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteStreamServiceImpl.java:334
@Scheduled(fixedRate = 10000)   //定时检测,清理错误的redis数据
public void execute(){
    ...
}
```

```text
// IInviteStreamService.java
（接口中没有 execute() 方法）
```

`InviteStreamServiceImpl` **实现了接口** `IInviteStreamService`，但 `execute()` 是 `public` 方法，且只存在于实现类，没有提升到接口。

### 4. 看是哪次提交把雷埋下

```bash
git log --since="7 days ago" --pretty=format:"%h|%ad|%s" --date=short
# ee04798b9 | 2026-05-05 | 新增 WVP→IAM 回调客户端 + 双密钥轮换支持 (TODO-4)
# 6a9415033 | 2026-05-05 | 修复 SIP Digest KD 构造: ...
```

```bash
git show ee04798b9 -- src/main/java/com/genersoft/iot/vmp/VManageBootstrap.java
```

```diff
 @EnableScheduling
+@EnableAsync
 @EnableCaching
```

`InviteStreamServiceImpl.java` 自身在最近一周**没有任何改动**（最后改动停留在 2026-04-13）。问题是 `@EnableAsync` 的副作用。

---

## 三、根因

### 3.1 `@EnableAsync` 默认走 JDK 动态代理

`@EnableAsync` 默认 `proxyTargetClass = false`。一旦启用，`AsyncAnnotationBeanPostProcessor` 会为**所有含有 `@Async` 方法的 Bean** 创建代理；当 Bean 实现了接口时，默认使用 **JDK 动态代理**（基于接口）。

`InviteStreamServiceImpl` 含有 `@Async` 方法（详见 3.3 节），且 `implements IInviteStreamService` ⇒ 走 JDK 代理 ⇒ 代理类只看得见**接口里**声明过的方法。

### 3.2 Spring AOP 的长期约束：`@Scheduled` 方法必须能从代理调用

`ScheduledAnnotationBeanPostProcessor` 在处理 `@Scheduled` 注解时，会调 `AopUtils.selectInvocableMethod(...)` 在**当前暴露的代理类型**上反射查找该方法。如果 Bean 已被包成 JDK 代理，而 `@Scheduled` 方法没在接口里，则查找失败、抛 `IllegalStateException`，启动期直接崩。

错误消息原文：

> `Either pull the method up to an interface or switch to CGLIB proxies by enforcing proxy-target-class mode in your configuration.`

这是 Spring AOP 长期就有的约束（`MethodIntrospector.selectInvocableMethod` / `AopUtils.selectInvocableMethod` 自 Spring 4.x 就存在），并非某个版本新加的"严格校验"。本案中：

`InviteStreamServiceImpl#execute()` 不在 `IInviteStreamService` 中 ⇒ JDK 代理上看不见 ⇒ 校验失败。

> 注：`AopUtils.selectInvocableMethod` 自 Spring 4.x 起就存在于 `ScheduledAnnotationBeanPostProcessor` 的调用链中。这是 Spring AOP 的**长期设计约束**，而不是某个版本引入的回归 bug —— 修复不需要等 Spring 升级，靠正确配置代理策略即可。

### 3.3 精确触发机制（已通过代码验证）

很多人会以为 `@EnableAsync` 会"全局加代理"，其实不是。它启用的是 `AsyncAnnotationBeanPostProcessor`，**只代理那些有 `@Async` 注解的 Bean**。本案中，`InviteStreamServiceImpl` 之所以会被代理，是因为它内部有一个 `@Async` 方法：

```java
// src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteStreamServiceImpl.java:50
@Async
@EventListener
public void onApplicationEvent(MediaDepartureEvent event) {
    // 流离开的处理
    ...
}
```

这是个早就存在的方法（不在 `ee04798b9` 提交里），但在 `@EnableAsync` 开启之前它**只是普通方法、不会被代理**；一旦 `@EnableAsync` 打开，它立即激活了对该 Bean 的代理生成。

完整触发链：

```
ee04798b9 引入 @EnableAsync (proxyTargetClass = false 默认)
      ↓
AsyncAnnotationBeanPostProcessor 激活
      ↓
扫描所有 Bean，发现 InviteStreamServiceImpl#onApplicationEvent 上有 @Async
      ↓
该 Bean 必须被代理；又因 implements IInviteStreamService
      ↓
默认走 JDK 动态代理（代理对象只暴露接口方法）
      ↓
ScheduledAnnotationBeanPostProcessor 接管:
  扫描 @Scheduled execute() → 反射查找代理类型上的同名方法
      ↓
execute() 不在 IInviteStreamService 接口中 → 找不到
      ↓
抛 IllegalStateException
      ↓
Bean 创建失败 → ApplicationContext 启动失败
      ↓
SpringApplication.run() 抛异常 → 异常冒泡到 main() → JVM 进程退出
      ↓
Docker restart: always 不断拉起 → 重启 40+ 次
```

> ⚠️ **关于退出码 0**：本案 `docker inspect` 看到的是 `ExitCode=0`。Spring Boot 启动失败时，**默认行为是未捕获异常导致 JVM 以退出码 1 结束**；如果项目中有 `ExitCodeGenerator` 或 `ExitCodeExceptionMapper` 将退出码映射为 0，则会走 `System.exit(0)` 路径。无论是哪种情况，**`ExitCode=0` + `RestartCount` 持续增长这个组合，绝对不能等同于"业务正常退出"——必须直接看应用日志**。
>
> 本案 Dockerfile 用的是 exec 形式 `ENTRYPOINT ["java", ...]`，Java 直接作为 PID 1，所以**不存在 entrypoint 脚本吞退出码的可能**；exit 0 的具体路径需要看代码里有没有注册 `ExitCodeGenerator`，本案未做进一步追溯。
>
> 注：`SpringApplication.exit()` 是 Spring Boot 提供给业务方"优雅关闭已运行上下文"的工具方法，**不是启动失败路径上的自动调用**。

**关键洞察**：本 bug 是两个独立时间点的代码"隔空相撞"——
- `@Async` 方法（早期就有）
- `@Scheduled` 方法不在接口里（早期就有）
- `@EnableAsync` 开关（2026-05-05 才打开）

前两条单独存在都不会有问题；第三条一打开，立刻把这一对儿"潜在不兼容"激活成启动期崩溃。这也是为什么 `git log InviteStreamServiceImpl.java` 看不到任何最近改动 —— 改的不是它，是它的"代理触发条件"。

### 3.4 为什么这个 bug 在开发联调阶段没暴露？

项目本身就是 Spring Boot 3.x，本地完整启动也必炸 —— 所以"本地跑过没事"这种解释不成立。真正原因更朴素：

**`@EnableAsync` 提交（2026-05-05）到 Docker 重建（2026-05-09）之间，没有人完整启动过应用上下文。**可能的工作模式：只跑编译 / 单元测试 / 接口契约测试，或本地 Docker 镜像未重新构建（用的是旧镜像）。直到这次容器重建拉新代码、走完 `SpringApplication.run()` 全流程，问题才暴露。

➡️ 这也是后面 6.x 节"复盘"里要强调的：**任何加在 `@SpringBootApplication` 启动类上的注解改动，提交前都应该至少跑一次完整 `SpringApplication.run()`**，哪怕只是本地 dev profile 起一下。

---

## 四、修复方案对比

| 维度 | 方案 1：`proxyTargetClass=true` | 方案 2：接口加 `execute()` | 方案 3：拆出独立 `@Component` |
| --- | --- | --- | --- |
| **改动范围** | 1 行（启动类） | 接口加方法 + 全项目排查同类隐患 | 新建一个类 + 重构注入 |
| **生效原理** | 全局切 CGLIB 子类代理 | 让 JDK 代理能看见 `execute()` | 定时任务类不实现接口，天然 CGLIB |
| **是否治本** | 治本（覆盖项目所有同类隐患） | 治标（只修一处） | 治本 + 架构改善 |
| **接口语义** | 不污染接口 | **污染接口**：内部定时逻辑被暴露 | 不污染接口 |
| **上游兼容性** | 高，贴近 Spring Boot 默认实践 | 修改了上游接口签名，merge 易冲突 | 引入新文件，merge 中等冲突 |
| **风险** | CGLIB 不能代理 `final`/`private` 方法（wvp 业务类几乎无 final，风险极低） | 漏排查则下次启动还会炸别处 | 需要保证组件被扫描到 |

### 最终采用：方案 1

```java
// src/main/java/com/genersoft/iot/vmp/VManageBootstrap.java
@EnableScheduling
@EnableAsync(proxyTargetClass = true)
@EnableCaching(proxyTargetClass = true)
```

`@EnableCaching` 一并加上 `proxyTargetClass = true`，确保**全局代理策略统一**，避免后续再踩同类坑。

---

## 五、验证

```powershell
# 1. 重建镜像
docker compose -f docker/docker-compose.yml build wvp

# 2. 重启容器
docker compose -f docker/docker-compose.yml up -d wvp

# 3. 观察日志（应看到正常启动而非 IllegalStateException）
docker logs -f docker-polaris-wvp-1

# 4. 确认状态稳定
docker inspect docker-polaris-wvp-1 --format "RestartCount={{.RestartCount}} Status={{.State.Status}}"
```

成功标志：

- 日志出现 `Started VManageBootstrap in X.XXX seconds`
- `docker ps` 中 `STATUS` 持续为 `Up X minutes` 而不是几秒重置
- `RestartCount` 不再增长

---

## 六、复盘与教训

### 6.1 在启动类上加注解的"蝴蝶效应"

`@SpringBootApplication` 启动类是全局配置入口，任何加在这里的 `@Enable*` 注解都会**作用于整个应用上下文**。`@EnableAsync` 看似只是为了给一两个 `@Async` 方法开绿灯，但它会**改变所有含 `@Async` 方法的 Bean 及其依赖链的代理策略**——这些 Bean 可能本来不需要代理，加上 `@EnableAsync` 后突然被包成 JDK 代理，原本"在实现类上、不在接口里"的 `@Scheduled` / `@Cacheable` / `@Transactional` 方法立刻暴露兼容性问题。

➡️ **加 `@EnableAsync` / `@EnableTransactionManagement` 等注解时，务必显式声明 `proxyTargetClass = true`**，与 Spring Boot 2.x+ 的默认行为保持一致。

### 6.2 Spring Boot 默认 vs Spring Framework 默认

- **Spring Boot 2.x+ 自动配置**：默认 `spring.aop.proxy-target-class=true`（CGLIB）
- **Spring Framework 原生 `@EnableAsync` / `@EnableCaching`**：默认 `proxyTargetClass=false`（JDK）

**两套默认值不一致**，本项目踩中的就是这个坑：用了原生注解，而没沿用 Spring Boot 默认。

### 6.3 `@Scheduled` 方法应避免暴露到业务接口

`execute()` 这种"内部清理任务"放在 service 实现类里、用 `@Scheduled` 注解触发，本身没问题；但要么：

1. 用 CGLIB 代理（绕过校验），或
2. 提取到独立的 `@Component` 定时任务类（架构上更清晰）

**不应该**为了让 JDK 代理能看见而把它塞进业务接口 —— 那会污染接口契约。

### 6.4 容器不断重启时的排查心法

1. **先看 `docker inspect` 的退出码 + RestartCount 组合**：
   - `RestartCount` 持续增长 ⇒ 一定是启动期失败，无论退出码是几
   - 退出码 0 **不代表正常** —— 可能来自 Spring Boot `ExitCodeGenerator` / 自定义 `System.exit(0)` / shell 包装的 entrypoint 等多种路径，光看退出码无法判断，必须配合应用日志
   - 非 0（137=SIGKILL/OOM、143=SIGTERM）⇒ 信号杀进程或资源限制
2. **再看应用日志而不是只看 `docker logs --tail 20`**：拉满 200~500 行，往前找第一处 ERROR
3. **关注异常链最底部的 `Caused by`**：根因通常在那里，且经常已经把修复方案写在错误信息里（比如本案）
4. **定位到怀疑文件后立刻看 `git log` 最近改动**：90% 的"突然崩"都是最近某次提交引入的

---

## 七、相关链接

- 问题提交：`ee04798b9` 新增 WVP→IAM 回调客户端 + 双密钥轮换支持 (TODO-4)
- 涉及文件：
  - `src/main/java/com/genersoft/iot/vmp/VManageBootstrap.java`
  - `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteStreamServiceImpl.java`
  - `src/main/java/com/genersoft/iot/vmp/gb28181/service/IInviteStreamService.java`
- Spring 官方文档：[Enable Async Support](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support-async)
- Spring 官方文档：[Annotation-based Container Configuration - Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)（解释 JDK vs CGLIB 代理选择规则与 `@Scheduled` 方法的可见性约束）
