plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Senshi"
    className = "Senshi"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
    implementation(project(":lib:playlistutils"))
}
