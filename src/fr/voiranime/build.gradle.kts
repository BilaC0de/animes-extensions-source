import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Voiranime"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl {
            custom("https://voiranime.cx")
        }
    }

    deeplink {
        host("voiranime.cx")
        host("voir-anime.to")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:voeextractor"))
}
