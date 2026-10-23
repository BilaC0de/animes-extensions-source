plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "My Running Man"
    className = "MyRunningMan"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("www.myrunningman.com")
        path("/ep/..*")
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:streamtapeextractor"))
}
