package ac.navro.proxy.check.api;

public record CheckViolation(String key,String baseName,String displayName,int checkVl,
                             boolean experimental,String verbose,String legacyMessage) { }
