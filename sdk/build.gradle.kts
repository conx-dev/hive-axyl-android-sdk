import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

val publishingGroupId = providers.gradleProperty("POM_GROUP_ID").getOrElse("io.github.conx-dev")
val publishingArtifactId = providers.gradleProperty("POM_ARTIFACT_ID").getOrElse("hive-axyl-android-sdk")
val publishingVersion = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0")

group = publishingGroupId
version = publishingVersion

android {
    namespace = "com.hiveaxyl.sdk"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/gen")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

val androidJavadocJar = tasks.register<Jar>("androidJavadocJar") {
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = publishingGroupId
            artifactId = publishingArtifactId
            version = publishingVersion
            artifact(androidJavadocJar)

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name = providers.gradleProperty("POM_NAME").getOrElse("Hive Axyl Android SDK")
                description = providers.gradleProperty("POM_DESCRIPTION")
                    .getOrElse("Hive Axyl Android SDK for game clients.")
                url = providers.gradleProperty("POM_URL")
                    .getOrElse("https://github.com/conx-dev/hive-axyl-android-sdk")

                licenses {
                    license {
                        name = providers.gradleProperty("POM_LICENSE_NAME").getOrElse("Proprietary")
                        url = providers.gradleProperty("POM_LICENSE_URL")
                            .getOrElse("https://github.com/conx-dev/hive-axyl-android-sdk")
                    }
                }

                developers {
                    developer {
                        id = providers.gradleProperty("POM_DEVELOPER_ID").getOrElse("hiveaxyl")
                        name = providers.gradleProperty("POM_DEVELOPER_NAME").getOrElse("Hive Axyl")
                        email = providers.gradleProperty("POM_DEVELOPER_EMAIL").getOrElse("developers@hiveaxyl.com")
                    }
                }

                scm {
                    connection = providers.gradleProperty("POM_SCM_CONNECTION")
                        .getOrElse("scm:git:https://github.com/conx-dev/hive-axyl-android-sdk.git")
                    developerConnection = providers.gradleProperty("POM_SCM_DEVELOPER_CONNECTION")
                        .getOrElse("scm:git:ssh://github.com/conx-dev/hive-axyl-android-sdk.git")
                    url = providers.gradleProperty("POM_SCM_URL")
                        .getOrElse("https://github.com/conx-dev/hive-axyl-android-sdk")
                }
            }
        }
    }

    repositories {
        maven {
            name = "mavenCentralBundle"
            url = uri(layout.buildDirectory.dir("maven-central-bundle-repository"))
        }

        val releaseRepositoryUrl = providers.gradleProperty("HIVE_AXYL_MAVEN_REPOSITORY_URL").orNull
        if (!releaseRepositoryUrl.isNullOrBlank()) {
            maven {
                name = "release"
                url = uri(releaseRepositoryUrl)
                credentials {
                    username = providers.gradleProperty("HIVE_AXYL_MAVEN_USERNAME").orNull.orEmpty()
                    password = providers.gradleProperty("HIVE_AXYL_MAVEN_PASSWORD").orNull.orEmpty()
                }
            }
        }
    }
}

signing {
    val signingKeyId = providers.gradleProperty("signingKeyId").orNull
        ?.removePrefix("0x")
        ?.takeLast(8)
    val signingKey = providers.gradleProperty("signingKey").orNull
        ?.replace("\\n", "\n")
    val signingPassword = providers.gradleProperty("signingPassword").orNull

    if (!signingKey.isNullOrBlank()) {
        if (signingKeyId.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        } else {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        }
    }

    setRequired {
        !publishingVersion.endsWith("SNAPSHOT") && gradle.taskGraph.allTasks.any { task ->
            task.name == "generateMavenCentralBundle" ||
                task.name == "publishReleasePublicationToMavenCentralBundleRepository" ||
                task.name == "publishReleasePublicationToReleaseRepository" ||
                task.name == "publish"
        }
    }
    sign(publishing.publications["release"])
}

val cleanMavenCentralBundleRepository = tasks.register<Delete>("cleanMavenCentralBundleRepository") {
    delete(layout.buildDirectory.dir("maven-central-bundle-repository"))
}

val publishReleasePublicationToMavenCentralBundleRepository = tasks.named(
    "publishReleasePublicationToMavenCentralBundleRepository",
    PublishToMavenRepository::class.java
) {
    dependsOn(cleanMavenCentralBundleRepository)
}

tasks.register<Zip>("generateMavenCentralBundle") {
    dependsOn(publishReleasePublicationToMavenCentralBundleRepository)
    from(publishReleasePublicationToMavenCentralBundleRepository.map { task -> task.repository.url })
    archiveFileName.set("$publishingArtifactId-$publishingVersion-maven-central.zip")
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:4.35.0")
    api("com.android.billingclient:billing:9.1.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
}
