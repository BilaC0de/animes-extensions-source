plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "UniqueStream"
    className = "UniqueStream"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:hlssegment"))
    implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
}
