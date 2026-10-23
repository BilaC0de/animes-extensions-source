plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Pi Fansubs"
    className = "PiFansubs"
    versionCode = 32
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://pifansubs.club"
}

dependencies {
    implementation(project(":lib:vidhideextractor"))
}
