介绍
------------------------------------
在优秀生态的技术体系中，通常都存在着一个十分优秀的扩展机制。例如：Eclipse 的插件机制、Spring 扩展机制。而 Hasor 也有着自己独特的扩展机制。

在 Hasor 的扩展体系在不同层次上提供了 4 个扩展机制，涉及到它们的形态是：

+----------------+------------------------------------------+
| **能力点**     | **形态**                                 |
+----------------+------------------------------------------+
| Module 机制    | `net.hasor.core.Module` 接口             |
+----------------+------------------------------------------+
| ApiBinder 机制 | `net.hasor.core.ApiBinder` 接口          |
+----------------+------------------------------------------+
| SPI 机制       | `net.hasor.core.spi.SpiTrigger` 接口     |
+----------------+------------------------------------------+
| 配置文件       | 配置文件加载机制                         |
+----------------+------------------------------------------+
