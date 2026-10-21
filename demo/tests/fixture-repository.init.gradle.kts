// 仅供隔离测试：从显式指定的本地 Maven fixture 读取 SDK，绝不用于正常接入。
val fixture = System.getenv("LINGORA_TEST_MAVEN_REPOSITORY")
    ?: error("Fixture tests require LINGORA_TEST_MAVEN_REPOSITORY")
settingsEvaluated {
    dependencyResolutionManagement.repositories {
        exclusiveContent {
            forRepository { maven { url = uri(fixture) } }
            filter { includeGroup("io.lingora") }
        }
    }
}
