plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HDS"
    className = "Hds"
    versionCode = 17
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://www.hds.quest"
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:vidhideextractor"))
}
