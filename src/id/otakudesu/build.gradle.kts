plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "OtakuDesu"
    className = "OtakuDesu"
    versionCode = 45
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
}
