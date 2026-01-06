# 介绍
SimpleMonitorSystem是一个仅用于测试跨链监管功能是否正常的简单监管系统服务。使用mvn clean package编译后会在文件夹根目录生成可执行的jar文件。

## MonitorAPI
包含与monitor-node相同的proto文件。

## MonitorSystemServer

与监管节点建立grpc通信的监管服务。运行后可选择接收到监管节点发送来的消息后返回成功（success）或失败（fail）。

## MonitorSystemClient

仅用于测试MonitorSystemServer功能是否正常。

## MonitorOrderClient

向监管节点发送监管指令的服务。通过修改MonitorOrderClient.java中第45行构造监管指令的代码，并编译运行来向监管节点发送不同的监管指令。
