package net.nestworld.modapi.domain;

/** <b>PLATFORM PROVIDER SPI — not part of the public Mod API.</b> Implemented by NestWorld Core. */
public interface DomainBackend {
    DomainHandle open(String name);
}
