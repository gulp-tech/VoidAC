// Отдельный Gradle-плагин для ядра Purpurbedrock 26.2.
//
// Сборка сделана по образцу purpurbedrock-npc/build.gradle.kts из этого же
// проекта: тот же toolchain (Java 25), те же репозитории и та же зависимость
// на API ядра со scope compileOnly, чтобы API НЕ попадал внутрь JAR.
//
// Существующая Maven-сборка (pom.xml) сохранена без изменений — этот файл
// добавляет второй, эквивалентный способ сборки под локальный API ядра.
//
// Версия API: по умолчанию 26.2-R0.1-SNAPSHOT — именно её публикует
// `./gradlew :purpur-api:publishToMavenLocal` из этого ядра
// (gradle.properties: mcVersion = 26.2). Переопределяется так:
//   ./gradlew build -PpurpurVersion="$(ls ~/.m2/repository/org/purpurmc/purpur/purpur-api)"

plugins {
    java
}

group = "ru.voidpe"
version = "2.0.4"
description = "VoidAntiCheat — античит для Bedrock-игроков Purpurbedrock 26.2"

// Версия API. Читается ИМЕННО из -PpurpurVersion / gradle.properties:
//   ./gradlew build -PpurpurVersion=26.2-R0.1-SNAPSHOT
// Если свойство не передано — используется версия, которую публикует это ядро
// (gradle.properties: mcVersion = 26.2  ->  26.2-R0.1-SNAPSHOT).
// Проверить, что свойство реально видно сборке:
//   ./gradlew -q properties -PpurpurVersion=X | grep '^version:'
val purpurApiVersion = providers.gradleProperty("purpurVersion").orElse("26.2-R0.1-SNAPSHOT")

// Задача-проверка: печатает фактическую координату API. Позволяет убедиться,
// что -PpurpurVersion реально прочитан, не запуская компиляцию:
//   ./gradlew -q printApiVersion -PpurpurVersion=26.2-R0.1-SNAPSHOT
tasks.register("printApiVersion") {
    doLast {
        println("[VoidAntiCheat] org.purpurmc.purpur:purpur-api:${purpurApiVersion.get()} (compileOnly)")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    // Локальный API, опубликованный из ядра: :purpur-api:publishToMavenLocal
    mavenLocal()
    maven("https://repo.purpurmc.org/snapshots")
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    // Только для компиляции: внутрь JAR не попадает.
    // Пустое/пробельное значение -PpurpurVersion ломает резолв — проверяем явно.
    val apiVersion = purpurApiVersion.get().trim()
    require(apiVersion.isNotEmpty()) { "-PpurpurVersion не может быть пустым" }
    compileOnly("org.purpurmc.purpur:purpur-api:$apiVersion")
}

tasks.withType<JavaCompile> {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
}

// Ресурсы не фильтруются: версия в plugin.yml — литерал, как в
// purpurbedrock-npc и purpurbedrock-anticheat этого проекта. Это снимает
// зависимость от `expand`/`filter` (в Gradle 9 `expand` устарел) и исключает
// ситуацию, когда в JAR уезжает незаменённый плейсхолдер.
tasks.processResources {
    filteringCharset = Charsets.UTF_8.name()
}

tasks.jar {
    archiveBaseName.set("VoidAntiCheat")
    archiveVersion.set(project.version.toString())
    // Ничего лишнего: в JAR только свои классы и ресурсы. Явный from(...)
    // здесь не нужен — main-вывод попадает в jar автоматически, а повторное
    // добавление давало бы дубли записей.
}

// Проверка, что API не попал в артефакт: если кто-то случайно заменит
// compileOnly на implementation, сборка упадёт здесь, а не на сервере.
tasks.register("verifyNoApiInJar") {
    dependsOn(tasks.jar)
    doLast {
        val jarFile = tasks.jar.get().archiveFile.get().asFile
        // Явный обход Enumeration: `zip.entries().asSequence()` в Kotlin DSL
        // не выводит тип (`Cannot infer type for type parameter 'T'`), поэтому
        // цикл написан в java-стиле и без хрупкого вывода типов.
        val leaked = mutableListOf<String>()
        java.util.zip.ZipFile(jarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("org/purpurmc/")
                    || name.startsWith("org/bukkit/")
                    || name.startsWith("io/papermc/")) {
                    leaked.add(name)
                }
            }
        }
        if (leaked.isNotEmpty()) {
            throw GradleException("В JAR попал API сервера: ${leaked.take(5)}")
        }
    }
}

tasks.build {
    dependsOn("verifyNoApiInJar")
}
