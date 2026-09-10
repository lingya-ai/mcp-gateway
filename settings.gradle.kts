pluginManagement {
    repositories {
        // 改为阿里云的镜像地址
        maven {
            isAllowInsecureProtocol = true
            setUrl("https://maven.aliyun.com/repository/gradle-plugin")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "mcp-gateway"
