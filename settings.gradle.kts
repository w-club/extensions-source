include(":src:zh:manhuarensuwa")
include(":src:zh:dm5suwa")
include(":core")

File(rootDir, "lib").listFiles()?.forEach { 
    if (it.isDirectory && it.name != ".gradle" && it.name != "build") {
        include("lib:${it.name}")
    }
}
