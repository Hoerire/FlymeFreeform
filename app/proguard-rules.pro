# libxposed 的服务端通过反射定位注入入口：类名与方法名一旦被混淆，框架就找不到它们，
# 表现为设置页一直「等待连接」，而模块在作用域进程里照常工作。
-keep class io.github.libxposed.service.** { *; }
-keepclassmembers class io.github.libxposed.service.** { *; }
-keep class io.github.libxposed.api.** { *; }
