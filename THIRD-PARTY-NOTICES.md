# 第三方说明

本模块参考 Supergateway 3.4.3（提交 `973c459`）的传输转换行为，使用 Kotlin 独立实现。

Supergateway 原项目采用 MIT License，版权声明如下：

```text
MIT License

Copyright (c) 2024 Supercorp

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

运行依赖还包括 MCP Java SDK、Reactor Netty、Netty、Jackson、picocli、SLF4J 和 Kotlin 标准库；
其许可证以发布包中对应依赖及各上游项目的许可证为准。GraalVM Native Image 构建产生的第三方许可证文件应随产物分发。
