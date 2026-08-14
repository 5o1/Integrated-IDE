# Third-Party Notices

Integrated Script is licensed under the MIT License; see LICENSE.

The release JAR does not bundle third-party code or libraries. It calls APIs
provided by these separately installed runtime dependencies:

| Project | License | Upstream |
| --- | --- | --- |
| Integrated Dynamics | MIT | https://github.com/CyclopsMC/IntegratedDynamics |
| Cyclops Core | MIT | https://github.com/CyclopsMC/CyclopsCore |
| CommonCapabilities | MIT | https://github.com/CyclopsMC/CommonCapabilities |

The license for each runtime dependency continues to apply to that dependency.
The MIT declarations above were verified from the META-INF/neoforge.mods.toml
metadata in the versions used to build this project.

This repository also retains build scaffolding from the NeoForge Mod Development
Kit, licensed under MIT; its original text is kept in TEMPLATE_LICENSE.txt.
The Gradle Wrapper files are licensed under Apache License 2.0, as stated in
their file headers. JUnit Jupiter is a development-only test dependency and is
not included in the release JAR.
