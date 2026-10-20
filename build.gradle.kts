plugins {
    // Ignition module plugin. 0.5.0 is the first release that understands the 8.3
    // `moduleDependencySpecs` form used below.
    id("io.ia.sdk.modl") version "0.5.0"
}

// SDK artifact version. Must be <= the Ignition version you deploy to.
val sdkVersion by extra("8.3.4")

allprojects {
    version = "0.1.0"

    repositories {
        maven {
            name = "IA Releases"
            url = uri("https://nexus.inductiveautomation.com/repository/inductiveautomation-releases")
        }
        maven {
            name = "IA Third Party"
            url = uri("https://nexus.inductiveautomation.com/repository/inductiveautomation-thirdparty")
        }
        mavenCentral()
    }
}

ignitionModule {
    name.set("Z-Wave JS Driver")
    fileName.set("ZWave-JS-Driver")
    id.set("com.arringtonautomation.ignition.zwave")
    moduleVersion.set("${project.version}")
    moduleDescription.set("OPC UA device driver for Z-Wave networks via a Z-Wave JS server.")
    requiredIgnitionVersion.set("8.3.0")

    // Shown by the gateway when the module is installed. Path relative to the project root.
    license.set("license.html")

    projectScopes.putAll(
        mapOf(
            ":gateway" to "G",
        ),
    )

    moduleDependencySpecs {
        register("com.inductiveautomation.opcua") {
            scope = "G"
            required = true
        }
    }

    hooks.putAll(
        mapOf(
            "com.arringtonautomation.ignition.zwave.ZWaveModuleHook" to "G",
        ),
    )

    // Unsigned build: the gateway needs -Dignition.allowunsignedmodules=true.
    skipModlSigning.set(true)
}

// ---------------------------------------------------------------------------
// Vendor name - the modl plugin has no setting for it, so it is injected into module.xml.
val moduleVendorName = "Arrington Automation"

val injectVendorName by tasks.registering {
    description = "Adds <vendorName> to module.xml, which the Gradle plugin has no setting for."
    dependsOn("writeModuleXml")

    val moduleXml = layout.buildDirectory.file("moduleContent/module.xml")
    val vendor = moduleVendorName

    doLast {
        val file = moduleXml.get().asFile
        if (!file.exists()) {
            logger.warn("module.xml not found at ${file.path}; vendor name not applied")
            return@doLast
        }
        val xml = file.readText()
        if (xml.contains("<vendorName>")) {
            return@doLast
        }
        val anchor = "</description>"
        if (!xml.contains(anchor)) {
            logger.warn("No <description> element in module.xml; vendor name not applied")
            return@doLast
        }
        file.writeText(xml.replace(anchor, "$anchor\n\t\t<vendorName>$vendor</vendorName>"))
        logger.lifecycle("module.xml: vendorName set to \"$vendor\"")
    }
}

tasks.named("zipModule") { dependsOn(injectVendorName) }
