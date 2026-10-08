import com.google.protobuf.gradle.*

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.5"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:4.35.1")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.35.1" }
    generateProtoTasks {
        all().configureEach {
            builtins { named("java") { option("lite") } }
        }
    }
}
