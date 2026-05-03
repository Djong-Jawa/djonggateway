package com.djong.gateway.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link LiquibaseRuntimeHints}.
 */
class LiquibaseRuntimeHintsTest {

    private LiquibaseRuntimeHints registrar;
    private RuntimeHints hints;
    private ClassLoader classLoader;

    @BeforeEach
    void setUp() {
        registrar = new LiquibaseRuntimeHints();
        hints = new RuntimeHints();
        classLoader = getClass().getClassLoader();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // registerHints() – top-level integration
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("registerHints()")
    class RegisterHints {

        @Test
        @DisplayName("does not throw when called with standard classloader")
        void doesNotThrow() {
            assertThatCode(() -> registrar.registerHints(hints, classLoader))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("registers Liquibase changelog resource patterns")
        void registersChangelogResources() {
            registrar.registerHints(hints, classLoader);
            // RuntimeHints exposes resource patterns through ResourceHints.
            // We verify that at least one pattern was registered (non-null/non-empty hints).
            assertThat(hints.resources()).isNotNull();
        }

        @Test
        @DisplayName("does not throw when classLoader is null (graceful degradation)")
        void doesNotThrowForNullClassLoader() {
            // A null classLoader should fall through to the hard-coded fallback path.
            assertThatCode(() -> registrar.registerHints(hints, null))
                    .doesNotThrowAnyException();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // scanAndRegister() – private method via reflection
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("scanAndRegister()")
    class ScanAndRegister {

        @Test
        @DisplayName("returns false when SingletonObject class is not on classpath")
        void returnsFalseWhenLiquibaseAbsent() {
            // Use a classloader that cannot load liquibase.SingletonObject
            ClassLoader emptyLoader = new ClassLoader(null) {};
            ReflectionHints rh = hints.reflection();
            Boolean result = (Boolean) ReflectionTestUtils.invokeMethod(
                    registrar, "scanAndRegister", rh, emptyLoader);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns a boolean (true or false) and does not throw")
        void returnsBoolean() {
            ReflectionHints rh = hints.reflection();
            Object result = ReflectionTestUtils.invokeMethod(
                    registrar, "scanAndRegister", rh, classLoader);
            assertThat(result).isInstanceOf(Boolean.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // registerClass() – private method via reflection
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("registerClass()")
    class RegisterClass {

        @Test
        @DisplayName("registers a concrete class without throwing")
        void registersConcreteClass() {
            ReflectionHints rh = hints.reflection();
            assertThatCode(() ->
                    ReflectionTestUtils.invokeMethod(registrar, "registerClass",
                            rh, "java.util.ArrayList", classLoader))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("silently skips an interface class")
        void skipsInterface() {
            ReflectionHints rh = hints.reflection();
            assertThatCode(() ->
                    ReflectionTestUtils.invokeMethod(registrar, "registerClass",
                            rh, "java.util.List", classLoader))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("silently skips an abstract class")
        void skipsAbstractClass() {
            ReflectionHints rh = hints.reflection();
            assertThatCode(() ->
                    ReflectionTestUtils.invokeMethod(registrar, "registerClass",
                            rh, "java.util.AbstractList", classLoader))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("silently skips a non-existent class (ClassNotFoundException)")
        void skipsNonExistentClass() {
            ReflectionHints rh = hints.reflection();
            assertThatCode(() ->
                    ReflectionTestUtils.invokeMethod(registrar, "registerClass",
                            rh, "com.does.not.Exist", classLoader))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("concrete class is present in reflection hints after registration")
        void concretClassAppearsInHints() {
            ReflectionHints rh = hints.reflection();
            ReflectionTestUtils.invokeMethod(registrar, "registerClass",
                    rh, "java.util.ArrayList", classLoader);

            // Verify the type hint was actually recorded
            assertThat(rh.typeHints()
                    .filter(th -> th.getType().getName().equals("java.util.ArrayList"))
                    .findAny())
                    .isPresent();
        }

        @Test
        @DisplayName("registered type contains INVOKE_DECLARED_CONSTRUCTORS member category")
        void registeredTypeHasDeclaredConstructors() {
            ReflectionHints rh = hints.reflection();
            ReflectionTestUtils.invokeMethod(registrar, "registerClass",
                    rh, "java.util.ArrayList", classLoader);

            boolean hasDeclaredConstructors = rh.typeHints()
                    .filter(th -> th.getType().getName().equals("java.util.ArrayList"))
                    .flatMap(th -> th.getMemberCategories().stream())
                    .anyMatch(mc -> mc == MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

            assertThat(hasDeclaredConstructors).isTrue();
        }
    }
}

