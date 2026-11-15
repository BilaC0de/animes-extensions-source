import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Voiranime"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "fr"
        baseUrl = "https://voiranime.cx"
    }

    deeplink {
        host("voiranime.cx")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
}
