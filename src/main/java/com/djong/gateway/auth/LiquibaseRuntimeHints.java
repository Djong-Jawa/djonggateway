package com.djong.gateway.auth;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Modifier;

/**
 * Registers Liquibase classes and resources needed at runtime in a GraalVM native image.
 *
 * Liquibase uses Scope.getSingleton(Class) which calls getDeclaredConstructor().newInstance()
 * on every SingletonObject implementation.  GraalVM strips unused reflective constructors
 * at build time, so every such class must be declared here.
 *
 * Primary strategy: scan the entire "liquibase" package for all SingletonObject subtypes.
 * Fallback  strategy: a hard-coded list covering Liquibase 4.28 / 4.29.
 */
public class LiquibaseRuntimeHints implements RuntimeHintsRegistrar {

    // ── known SingletonObject implementations in Liquibase 4.28 / 4.29 ──────────
    private static final String[] KNOWN_SINGLETONS = {
            "liquibase.change.ChangeFactory",
            "liquibase.command.CommandFactory",
            "liquibase.configuration.LiquibaseConfiguration",
            "liquibase.database.DatabaseFactory",
            "liquibase.database.LiquibaseTableNamesFactory",
            "liquibase.datatype.DataTypeFactory",
            "liquibase.diff.compare.DatabaseObjectComparatorFactory",
            "liquibase.diff.DiffGeneratorFactory",
            "liquibase.executor.ExecutorService",
            "liquibase.hub.HubServiceFactory",           // removed in 4.29 – skipped silently
            "liquibase.license.LicenseServiceFactory",
            "liquibase.lockservice.LockServiceFactory",
            "liquibase.logging.core.DefaultLoggerConfiguration",
            "liquibase.logging.mdc.MdcManager",
            "liquibase.parser.ChangeLogParserFactory",
            "liquibase.parser.SqlParserFactory",
            "liquibase.precondition.PreconditionFactory",
            "liquibase.report.ShowSummaryGeneratorFactory",
            "liquibase.serializer.ChangeLogSerializerFactory",
            "liquibase.servicelocator.ServiceLocator",
            "liquibase.snapshot.SnapshotGeneratorFactory",
            "liquibase.sqlgenerator.SqlGeneratorFactory",
            "liquibase.structure.DatabaseObjectFactory",
            "liquibase.ui.LoggerUIService",
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // ── Classpath resources ──────────────────────────────────────────────
        hints.resources()
                .registerPattern("db/changelog-master.xml")
                .registerPattern("db/changelogs/*.xml")
                .registerPattern("db/**/*.xml");

        ReflectionHints rh = hints.reflection();


        // ── Primary: scan the entire liquibase package automatically ─────────
        // Catches any SingletonObject implementation regardless of Liquibase version.
        boolean scanned = scanAndRegister(rh, classLoader);

        // ── Fallback: hard-coded list (covers the case where scanning fails) ─
        if (!scanned) {
            for (String name : KNOWN_SINGLETONS) {
                registerClass(rh, name, classLoader);
            }
        } else {
            // Always register the hard-coded list on top to be safe
            for (String name : KNOWN_SINGLETONS) {
                registerClass(rh, name, classLoader);
            }
        }
    }

    /**
     * Scans all classes under "liquibase" that implement SingletonObject and registers
     * their constructors + fields for reflection.
     *
     * @return true if scanning succeeded, false on any error
     */
    private boolean scanAndRegister(ReflectionHints rh, ClassLoader classLoader) {
        try {
            Class<?> singletonType = Class.forName("liquibase.SingletonObject", false, classLoader);

            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.setResourceLoader(new PathMatchingResourcePatternResolver(classLoader));
            scanner.addIncludeFilter(new AssignableTypeFilter(singletonType));

            for (BeanDefinition bd : scanner.findCandidateComponents("liquibase")) {
                registerClass(rh, bd.getBeanClassName(), classLoader);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void registerClass(ReflectionHints hints, String className, ClassLoader classLoader) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            // Only register concrete classes – interfaces / abstract classes cannot be instantiated
            if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())) {
                hints.registerType(clazz,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.DECLARED_FIELDS);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            // Class absent in this Liquibase version – skip silently
        }
    }
}

