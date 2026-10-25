# NestWorld external modules

NestWorldCore creates `nestworld-modules/` beside the server's `mods/` directory on first start.
It scans regular `*.jar` files directly inside that directory at every server start. Subdirectories
are not module dependency directories, and installation or replacement always requires a full JVM
restart.

Every module JAR contains `META-INF/nestworld/module.json` and an entrypoint implementing
`net.nestworld.module.NestWorldModule`. Compile against the `:module-spi` artifact with
`compileOnly`; never package `net/nestworld/**` classes inside the module JAR. Module code is trusted
server code, but it receives Core resources through declared, versioned `ModuleContext` capabilities.
Publish the API for an external build with
`./gradlew :module-spi:publishModuleSpiPublicationToMavenLocal`; its Maven coordinates are
`net.nestworld:nestworld-module-spi:1.0.0-SNAPSHOT`.

The loader refuses duplicate ids, incompatible SPI/capability or mappings versions, missing required
dependencies, dependency cycles, parent class shadowing, reserved packages, Forge mods, Mixins,
access transformers, nested jars, non-SPI NestWorld references and absent SPI members. One invalid
non-critical module does not stop independent modules or the server. Command names cannot replace an
existing dispatcher root. Listener registration uses the typed lambda API so callbacks work from the
module classloader.

## Fast Transition

Build the deployable SRG JAR:

```bash
./gradlew :forge:fastTransitionModuleJar
```

Copy `projects/forge/build/fastTransitionModule/fast-transition-1.0.0.jar` to
`<server>/nestworld-modules/fast-transition-1.0.0.jar`, then restart the server. The module is absent
when the JAR is absent. Use `-Dnestworld.modules.external=false` to disable all external module
discovery for one server lifetime, or `-Dnestworld.modules.dir=/absolute/path` to override the folder.
