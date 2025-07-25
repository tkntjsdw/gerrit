"""
This module lists the external dependencies of the Gerrit project.
"""

load("//tools/bzl:maven_jar.bzl", "GERRIT", "maven_jar")

CAFFEINE_VERS = "2.9.2"
ANTLR_VERS = "3.5.2"
COMMONMARK_VERSION = "0.21.0"
GREENMAIL_VERS = "1.5.5"
MAIL_VERS = "1.6.0"
MIME4J_VERS = "0.8.1"
OW2_VERS = "9.7"
AUTO_VALUE_GSON_VERSION = "1.3.1"
PROLOG_VERS = "1.4.4"
PROLOG_REPO = GERRIT
GITILES_VERS = "1.6.0"
GITILES_REPO = GERRIT

# When updating Bouncy Castle, also update it in bazlets.
BC_VERS = "1.80"
HTTPCOMP_VERS = "4.5.14"
JETTY_VERS = "9.4.53.v20231009"
BYTE_BUDDY_VERSION = "1.14.9"
ROARING_BITMAP_VERSION = "0.9.44"

def java_dependencies():
    """
    This method lists the maven jars used in the Gerrit project.
    """
    maven_jar(
        name = "java-runtime",
        artifact = "org.antlr:antlr-runtime:" + ANTLR_VERS,
        sha1 = "cd9cd41361c155f3af0f653009dcecb08d8b4afd",
    )

    maven_jar(
        name = "stringtemplate",
        artifact = "org.antlr:stringtemplate:4.0.2",
        sha1 = "e28e09e2d44d60506a7bcb004d6c23ff35c6ac08",
    )

    maven_jar(
        name = "org-antlr",
        artifact = "org.antlr:antlr:" + ANTLR_VERS,
        sha1 = "c4a65c950bfc3e7d04309c515b2177c00baf7764",
    )

    maven_jar(
        name = "antlr27",
        artifact = "antlr:antlr:2.7.7",
        attach_source = False,
        sha1 = "83cd2cd674a217ade95a4bb83a8a14f351f48bd0",
    )

    maven_jar(
        name = "aopalliance",
        artifact = "aopalliance:aopalliance:1.0",
        sha1 = "0235ba8b489512805ac13a8f9ea77a1ca5ebe3e8",
    )

    maven_jar(
        name = "javax_inject",
        artifact = "javax.inject:javax.inject:1",
        sha1 = "6975da39a7040257bd51d21a231b76c915872d38",
    )

    maven_jar(
        name = "servlet-api",
        artifact = "javax.servlet:javax.servlet-api:3.1.0",
        sha1 = "3cd63d075497751784b2fa84be59432f4905bf7c",
    )

    maven_jar(
        name = "jakarta-inject-api",
        artifact = "jakarta.inject:jakarta.inject-api:2.0.1",
        sha1 = "4c28afe1991a941d7702fe1362c365f0a8641d1e",
    )

    # JGit's transitive dependencies

    maven_jar(
        name = "javaewah",
        artifact = "com.googlecode.javaewah:JavaEWAH:1.1.12",
        attach_source = False,
        sha1 = "9feecc2b24d6bc9ff865af8d082f192238a293eb",
    )

    maven_jar(
        name = "caffeine",
        artifact = "com.github.ben-manes.caffeine:caffeine:" + CAFFEINE_VERS,
        sha1 = "0a17ed335e0ce2d337750772c0709b79af35a842",
    )

    maven_jar(
        name = "guava-failureaccess",
        artifact = "com.google.guava:failureaccess:1.0.1",
        sha1 = "1dcf1de382a0bf95a3d8b0849546c88bac1292c9",
    )

    maven_jar(
        name = "json-smart",
        artifact = "net.minidev:json-smart:1.1.1",
        sha1 = "24a2f903d25e004de30ac602c5b47f2d4e420a59",
    )

    maven_jar(
        name = "args4j",
        artifact = "args4j:args4j:2.33",
        sha1 = "bd87a75374a6d6523de82fef51fc3cfe9baf9fc9",
    )

    maven_jar(
        name = "commons-codec",
        artifact = "commons-codec:commons-codec:1.15",
        sha1 = "49d94806b6e3dc933dacbd8acb0fdbab8ebd1e5d",
    )

    # When upgrading commons-compress, also upgrade tukaani-xz
    maven_jar(
        name = "commons-compress",
        artifact = "org.apache.commons:commons-compress:1.25.0",
        sha1 = "9d35aec423da6c8a7f93d7e9e1c6b1d9fe14bb5e",
    )

    maven_jar(
        name = "commons-lang3",
        artifact = "org.apache.commons:commons-lang3:3.8.1",
        sha1 = "6505a72a097d9270f7a9e7bf42c4238283247755",
    )

    maven_jar(
        name = "commons-text",
        artifact = "org.apache.commons:commons-text:1.2",
        sha1 = "74acdec7237f576c4803fff0c1008ab8a3808b2b",
    )

    maven_jar(
        name = "commons-dbcp",
        artifact = "commons-dbcp:commons-dbcp:1.4",
        sha1 = "30be73c965cc990b153a100aaaaafcf239f82d39",
    )

    # Transitive dependency of commons-dbcp, do not update without
    # also updating commons-dbcp
    maven_jar(
        name = "commons-pool",
        artifact = "commons-pool:commons-pool:1.5.5",
        sha1 = "7d8ffbdc47aa0c5a8afe5dc2aaf512f369f1d19b",
    )

    maven_jar(
        name = "commons-net",
        artifact = "commons-net:commons-net:3.6",
        sha1 = "b71de00508dcb078d2b24b5fa7e538636de9b3da",
    )

    maven_jar(
        name = "commons-validator",
        artifact = "commons-validator:commons-validator:1.6",
        sha1 = "e989d1e87cdd60575df0765ed5bac65c905d7908",
    )

    maven_jar(
        name = "automaton",
        artifact = "dk.brics:automaton:1.12-1",
        sha1 = "959a0c62f9a5c2309e0ad0b0589c74d69e101241",
    )

    # commonmark must match the version used in Gitiles
    maven_jar(
        name = "commonmark",
        artifact = "org.commonmark:commonmark:" + COMMONMARK_VERSION,
        sha1 = "c98f0473b17c87fe4fa2fc62a7c6523a2fe018f0",
    )

    maven_jar(
        name = "cm-autolink",
        artifact = "org.commonmark:commonmark-ext-autolink:" + COMMONMARK_VERSION,
        sha1 = "55c0312cf443fa3d5af0daeeeca00d6deee3cf90",
    )

    maven_jar(
        name = "gfm-strikethrough",
        artifact = "org.commonmark:commonmark-ext-gfm-strikethrough:" + COMMONMARK_VERSION,
        sha1 = "953f4b71e133a98fcca93f3c3f4e58b895b76d1f",
    )

    maven_jar(
        name = "gfm-tables",
        artifact = "org.commonmark:commonmark-ext-gfm-tables:" + COMMONMARK_VERSION,
        attach_source = False,
        sha1 = "fb7d65fa89a4cfcd2f51535d2549b570cf1dbd1a",
    )

    maven_jar(
        name = "flexmark-all-lib",
        artifact = "com.vladsch.flexmark:flexmark-all:0.64.0:lib",
        attach_source = False,
        sha1 = "de92cef20c1f61681a3c8f64dd5975fbd3125049",
    )

    # Transitive dependency of flexmark and gitiles
    maven_jar(
        name = "autolink",
        artifact = "org.nibor.autolink:autolink:0.10.0",
        sha1 = "6579ea7079be461e5ffa99f33222a632711cc671",
    )

    maven_jar(
        name = "greenmail",
        artifact = "com.icegreen:greenmail:" + GREENMAIL_VERS,
        sha1 = "9ea96384ad2cb8118c22f493b529eb72c212691c",
    )

    maven_jar(
        name = "mail",
        artifact = "com.sun.mail:javax.mail:" + MAIL_VERS,
        sha1 = "a055c648842c4954c1f7db7254f45d9ad565e278",
    )

    maven_jar(
        name = "mime4j-core",
        artifact = "org.apache.james:apache-mime4j-core:" + MIME4J_VERS,
        sha1 = "c62dfe18a3b827a2c626ade0ffba44562ddf3f61",
    )

    maven_jar(
        name = "mime4j-dom",
        artifact = "org.apache.james:apache-mime4j-dom:" + MIME4J_VERS,
        sha1 = "f2d653c617004193f3350330d907f77b60c88c56",
    )

    maven_jar(
        name = "jsoup",
        artifact = "org.jsoup:jsoup:1.14.3",
        sha1 = "c43a81e18e6d0eb71951aa031d55d5c293c531a6",
    )

    maven_jar(
        name = "ow2-asm",
        artifact = "org.ow2.asm:asm:" + OW2_VERS,
        sha1 = "073d7b3086e14beb604ced229c302feff6449723",
    )

    maven_jar(
        name = "ow2-asm-analysis",
        artifact = "org.ow2.asm:asm-analysis:" + OW2_VERS,
        sha1 = "e4a258b7eb96107106c0599f0061cfc1832fe07a",
    )

    maven_jar(
        name = "ow2-asm-commons",
        artifact = "org.ow2.asm:asm-commons:" + OW2_VERS,
        sha1 = "e86dda4696d3c185fcc95d8d311904e7ce38a53f",
    )

    maven_jar(
        name = "ow2-asm-tree",
        artifact = "org.ow2.asm:asm-tree:" + OW2_VERS,
        sha1 = "e446a17b175bfb733b87c5c2560ccb4e57d69f1a",
    )

    maven_jar(
        name = "ow2-asm-util",
        artifact = "org.ow2.asm:asm-util:" + OW2_VERS,
        sha1 = "c0655519f24d92af2202cb681cd7c1569df6ead6",
    )

    maven_jar(
        name = "auto-value-gson-runtime",
        artifact = "com.ryanharter.auto.value:auto-value-gson-runtime:" + AUTO_VALUE_GSON_VERSION,
        sha1 = "addda2ae6cce9f855788274df5de55dde4de7b71",
    )

    maven_jar(
        name = "auto-value-gson-extension",
        artifact = "com.ryanharter.auto.value:auto-value-gson-extension:" + AUTO_VALUE_GSON_VERSION,
        sha1 = "0c4c01a3e10e5b10df2e5f5697efa4bb3f453ac1",
    )

    maven_jar(
        name = "auto-value-gson-factory",
        artifact = "com.ryanharter.auto.value:auto-value-gson-factory:" + AUTO_VALUE_GSON_VERSION,
        sha1 = "9ed8d79144ee8d60cc94cc11f847b5ed8ee9f19c",
    )

    maven_jar(
        name = "javapoet",
        artifact = "com.squareup:javapoet:1.13.0",
        sha1 = "d6562d385049f35eb50403fa86bb11cce76b866a",
    )

    maven_jar(
        name = "autotransient",
        artifact = "io.sweers.autotransient:autotransient:1.0.0",
        sha1 = "38b1c630b8e76560221622289f37be40105abb3d",
    )

    maven_jar(
        name = "mime-util",
        artifact = "eu.medsea.mimeutil:mime-util:2.1.3",
        attach_source = False,
        sha1 = "0c9cfae15c74f62491d4f28def0dff1dabe52a47",
    )

    maven_jar(
        name = "prolog-runtime",
        artifact = "com.googlecode.prolog-cafe:prolog-runtime:" + PROLOG_VERS,
        attach_source = False,
        repository = PROLOG_REPO,
        sha1 = "e9a364f4233481cce63239e8e68a6190c8f58acd",
    )

    maven_jar(
        name = "prolog-compiler",
        artifact = "com.googlecode.prolog-cafe:prolog-compiler:" + PROLOG_VERS,
        attach_source = False,
        repository = PROLOG_REPO,
        sha1 = "570295026f6aa7b905e423d107cb2e081eecdc04",
    )

    maven_jar(
        name = "prolog-io",
        artifact = "com.googlecode.prolog-cafe:prolog-io:" + PROLOG_VERS,
        attach_source = False,
        repository = PROLOG_REPO,
        sha1 = "1f25c4e27d22bdbc31481ee0c962a2a2853e4428",
    )

    maven_jar(
        name = "cafeteria",
        artifact = "com.googlecode.prolog-cafe:prolog-cafeteria:" + PROLOG_VERS,
        attach_source = False,
        repository = PROLOG_REPO,
        sha1 = "0e6c2deeaf5054815a561cbd663566fd59b56c6c",
    )

    maven_jar(
        name = "guava-retrying",
        artifact = "com.github.rholder:guava-retrying:2.0.0",
        sha1 = "974bc0a04a11cc4806f7c20a34703bd23c34e7f4",
    )

    maven_jar(
        name = "jsr305",
        artifact = "com.google.code.findbugs:jsr305:3.0.1",
        sha1 = "f7be08ec23c21485b9b5a1cf1654c2ec8c58168d",
    )

    maven_jar(
        name = "blame-cache",
        artifact = "com.google.gitiles:blame-cache:" + GITILES_VERS,
        attach_source = False,
        repository = GITILES_REPO,
        sha1 = "e110f1129a31a0bbb76c28da2a1770b234f1a755",
    )

    maven_jar(
        name = "gitiles-servlet",
        artifact = "com.google.gitiles:gitiles-servlet:" + GITILES_VERS,
        repository = GITILES_REPO,
        sha1 = "52441c05b83291898da051591036d0d55e1f3501",
    )

    maven_jar(
        name = "html-types",
        artifact = "com.google.common.html.types:types:1.0.8",
        sha1 = "9e9cf7bc4b2a60efeb5f5581fe46d17c068e0777",
    )

    maven_jar(
        name = "icu4j",
        artifact = "com.ibm.icu:icu4j:77.1",
        sha1 = "38693cf0b1d7362a8b726af74dc06026a7c23809",
    )

    maven_jar(
        name = "bcprov",
        artifact = "org.bouncycastle:bcprov-jdk18on:" + BC_VERS,
        sha1 = "e22100b41042decf09cab914a5af8d2c57b5ac4a",
    )

    maven_jar(
        name = "bcpg",
        artifact = "org.bouncycastle:bcpg-jdk18on:" + BC_VERS,
        sha1 = "163889a825393854dbe7dc52f1a8667e715e9859",
    )

    maven_jar(
        name = "bcpkix",
        artifact = "org.bouncycastle:bcpkix-jdk18on:" + BC_VERS,
        sha1 = "5277dfaaef2e92ce1d802499599a0ca7488f86e6",
    )

    maven_jar(
        name = "bcutil",
        artifact = "org.bouncycastle:bcutil-jdk18on:" + BC_VERS,
        sha1 = "b95726d1d49a0c65010c59a3e6640311d951bfd1",
    )

    maven_jar(
        name = "fluent-hc",
        artifact = "org.apache.httpcomponents:fluent-hc:" + HTTPCOMP_VERS,
        sha1 = "81a16abc0d5acb5016d5b46d4b197b53c3d6eb93",
    )

    maven_jar(
        name = "httpclient",
        artifact = "org.apache.httpcomponents:httpclient:" + HTTPCOMP_VERS,
        sha1 = "1194890e6f56ec29177673f2f12d0b8e627dec98",
    )

    maven_jar(
        name = "httpcore",
        artifact = "org.apache.httpcomponents:httpcore:4.4.16",
        sha1 = "51cf043c87253c9f58b539c9f7e44c8894223850",
    )

    # Test-only dependencies below.
    maven_jar(
        name = "junit",
        artifact = "junit:junit:4.12",
        sha1 = "2973d150c0dc1fefe998f834810d68f278ea58ec",
    )

    maven_jar(
        name = "hamcrest-core",
        artifact = "org.hamcrest:hamcrest-core:1.3",
        sha1 = "42a25dc3219429f0e5d060061f71acb49bf010a0",
    )

    maven_jar(
        name = "diffutils",
        artifact = "com.googlecode.java-diff-utils:diffutils:1.3.0",
        sha1 = "7e060dd5b19431e6d198e91ff670644372f60fbd",
    )

    maven_jar(
        name = "jetty-servlet",
        artifact = "org.eclipse.jetty:jetty-servlet:" + JETTY_VERS,
        sha1 = "6670d6a54cdcaedd8090e8cf420fd5dd7d08e859",
    )

    maven_jar(
        name = "jetty-security",
        artifact = "org.eclipse.jetty:jetty-security:" + JETTY_VERS,
        sha1 = "6fbc8ebe9046954dc2f51d4ba69c8f8344b05f7f",
    )

    maven_jar(
        name = "jetty-server",
        artifact = "org.eclipse.jetty:jetty-server:" + JETTY_VERS,
        sha1 = "8b0e761a0b359db59dae77c00b4213b0586cb994",
    )

    maven_jar(
        name = "jetty-jmx",
        artifact = "org.eclipse.jetty:jetty-jmx:" + JETTY_VERS,
        sha1 = "f0392f756b59f65ea7d6be41bf7a2f7b2c7c98d5",
    )

    maven_jar(
        name = "jetty-http",
        artifact = "org.eclipse.jetty:jetty-http:" + JETTY_VERS,
        sha1 = "87faf21eb322753f0527bcb88c43e67044786369",
    )

    maven_jar(
        name = "jetty-io",
        artifact = "org.eclipse.jetty:jetty-io:" + JETTY_VERS,
        sha1 = "70cf7649b27c964ad29bfddf58f3bfe0d30346cf",
    )

    maven_jar(
        name = "jetty-util",
        artifact = "org.eclipse.jetty:jetty-util:" + JETTY_VERS,
        sha1 = "f72bb4f687b4454052c6f06528ba9910714df947",
    )

    maven_jar(
        name = "jetty-util-ajax",
        artifact = "org.eclipse.jetty:jetty-util-ajax:" + JETTY_VERS,
        sha1 = "4d20f6206eb7747293697c5f64c2dc5bf4bd54a4",
        src_sha1 = "1aed8017c3c8a449323901639de6b4eb3b1f02ea",
    )

    maven_jar(
        name = "asciidoctor",
        artifact = "org.asciidoctor:asciidoctorj:1.5.7",
        sha1 = "8e8c1d8fc6144405700dd8df3b177f2801ac5987",
    )

    maven_jar(
        name = "javax-activation",
        artifact = "javax.activation:activation:1.1.1",
        sha1 = "485de3a253e23f645037828c07f1d7f1af40763a",
    )

    maven_jar(
        name = "mockito",
        artifact = "org.mockito:mockito-core:5.6.0",
        sha1 = "550b7a0eb22e1d72d33dcc2e5ef6954f73100d76",
    )

    maven_jar(
        name = "bytebuddy",
        artifact = "net.bytebuddy:byte-buddy:" + BYTE_BUDDY_VERSION,
        sha1 = "b69e7fff6c473d3ed2b489cdfd673a091fd94226",
    )

    maven_jar(
        name = "bytebuddy-agent",
        artifact = "net.bytebuddy:byte-buddy-agent:" + BYTE_BUDDY_VERSION,
        sha1 = "dfb8707031008535048bad2b69735f46d0b6c5e5",
    )

    maven_jar(
        name = "objenesis",
        artifact = "org.objenesis:objenesis:3.0.1",
        sha1 = "11cfac598df9dc48bb9ed9357ed04212694b7808",
    )

    maven_jar(
        name = "roaringbitmap",
        artifact = "org.roaringbitmap:RoaringBitmap:" + ROARING_BITMAP_VERSION,
        sha1 = "d25b4bcb67193d587f6e0617da2c6f84e2d02a9c",
    )

    maven_jar(
        name = "roaringbitmap-shims",
        artifact = "org.roaringbitmap:shims:" + ROARING_BITMAP_VERSION,
        sha1 = "e22be0d690a99c046bf9f57106065a77edad1eda",
    )
