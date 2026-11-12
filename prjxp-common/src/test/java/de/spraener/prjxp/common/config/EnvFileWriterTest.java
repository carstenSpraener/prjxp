package de.spraener.prjxp.common.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class EnvFileWriterTest {
    @TempDir
    Path directory;

    @Test
    void createsFileAndParentsWithKeysInInsertionOrder() throws Exception {
        Path file = directory.resolve("nested").resolve(".env");
        EnvFileWriter writer = new EnvFileWriter(file);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Z", "last");
        values.put("A", "first");
        writer.upsert(values);
        assertThat(writer.getEnvFile()).isEqualTo(file);
        assertThat(Files.readString(file)).isEqualTo("Z=last\nA=first\n");
        assertThat(writer.read()).containsExactlyEntriesOf(values);
        assertThat(writer.hasKey("Z")).isTrue();
        assertThat(writer.hasKey("MISSING")).isFalse();
    }

    @Test
    void preservesForeignTextMixedEndingsAndPosition() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "# A=comment\r\nA=1\n\n  B = old   # trailing\r\n//// comment\nforeign line\r\nC=3");
        new EnvFileWriter(file).upsert("B", "new");
        assertThat(Files.readString(file))
                .isEqualTo("# A=comment\r\nA=1\n\n  B = new   # trailing\r\n//// comment\nforeign line\r\nC=3");
    }

    @Test
    void appendsUsingDominantEndingWithoutJoiningLastLine() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "# header\r\nA=1\r\nB=2");
        new EnvFileWriter(file).upsert("C", "3");
        assertThat(Files.readString(file)).isEqualTo("# header\r\nA=1\r\nB=2\r\nC=3\r\n");
    }

    @Test
    void sameValueDoesNotChangeBytesOrTimestamp() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, " K = \"a b\"  # keep\r\n");
        FileTime timestamp = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(file, timestamp);
        new EnvFileWriter(file).upsert("K", "a b");
        assertThat(Files.readString(file)).isEqualTo(" K = \"a b\"  # keep\r\n");
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(timestamp);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain", "a b#c", "x\"y", "\"literal\"", "a=b", "C:\\a\\b", " leading ", "'single'", "$literal"})
    void roundTripsUsingActualDotenvLoader(String value) throws Exception {
        Path file = directory.resolve(".env");
        EnvFileWriter writer = new EnvFileWriter(file);
        writer.upsert("PRJXP_SETUP_TEST_VALUE", value);
        assertThat(writer.read().get("PRJXP_SETUP_TEST_VALUE")).isEqualTo(value);
        assertThat(Dotenv.configure().directory(directory.toString()).load()
                .entries(Dotenv.Filter.DECLARED_IN_ENV_FILE))
                .anySatisfy(entry -> {
                    assertThat(entry.getKey()).isEqualTo("PRJXP_SETUP_TEST_VALUE");
                    assertThat(entry.getValue()).isEqualTo(value);
                });
    }

    @Test
    void readsOnlyAssignmentsAndMatchesLoaderSingleQuoteSemantics() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "# A=not-a-key\n//// B=comment\nA=plain # comment\nB=\"a # b\"\nC='single'\nD=\n");
        assertThat(new EnvFileWriter(file).read()).containsExactly(
                entry("A", "plain"), entry("B", "a # b"), entry("C", "'single'"), entry("D", ""));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "A B", "A=B", "1KEY", "A.B", "A-B", "A\nB"})
    void invalidKeyDoesNotWrite(String key) throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "SAFE=value\n");
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvFileWriter(file).upsert(key, "new"));
        assertThat(Files.readString(file)).isEqualTo("SAFE=value\n");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"a\nb", "a\rb", "a\u0000b", "x\"y#z"})
    void unrepresentableValueDoesNotWrite(String value) throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "SAFE=value\n");
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvFileWriter(file).upsert("SAFE", value));
        assertThat(Files.readString(file)).isEqualTo("SAFE=value\n");
    }

    @Test
    void validatesAllEntriesBeforeChangingAnything() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "A=old\n");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("A", "new");
        values.put("INVALID KEY", "value");
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvFileWriter(file).upsert(values));
        assertThat(Files.readString(file)).isEqualTo("A=old\n");
    }

    @Test
    void duplicateRequestedKeyPreventsWriteAndReadReportsAmbiguity() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "A=one\nA=two\n");
        EnvFileWriter writer = new EnvFileWriter(file);
        assertThatIOException().isThrownBy(() -> writer.upsert("A", "new")).withMessageContaining("A");
        assertThatIOException().isThrownBy(writer::read).withMessageContaining("A");
        assertThatIOException().isThrownBy(() -> writer.hasKey("A")).withMessageContaining("A");
        assertThat(Files.readString(file)).isEqualTo("A=one\nA=two\n");
    }

    @Test
    void duplicateForeignKeysRemainUnchangedDuringOtherUpsert() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "A=one\nA=two\n");
        new EnvFileWriter(file).upsert("B", "new");
        assertThat(Files.readString(file)).isEqualTo("A=one\nA=two\nB=new\n");
    }

    @Test
    void missingFileReadsEmptyAndEmptyUpsertDoesNotCreateIt() throws Exception {
        Path file = directory.resolve(".env");
        EnvFileWriter writer = new EnvFileWriter(file);
        assertThat(writer.read()).isEmpty();
        writer.upsert(Map.of());
        assertThat(file).doesNotExist();
    }

    @Test
    void atomicMoveFailurePreservesOriginalAndCleansTemporaryFile() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "A=old\n");
        EnvFileWriter writer = new EnvFileWriter(file) {
            @Override
            protected void replace(Path temporary, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "test");
            }
        };
        assertThatIOException().isThrownBy(() -> writer.upsert("A", "new"));
        assertThat(Files.readString(file)).isEqualTo("A=old\n");
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).containsExactly(file);
        }
    }

    @Test
    void permissionsFailurePreservesOriginalAndCleansTemporaryFile() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "A=old\n");
        EnvFileWriter writer = new EnvFileWriter(file) {
            @Override
            protected void preparePermissions(Path temporary, Path target) throws IOException {
                throw new IOException("test permissions");
            }
        };
        assertThatIOException().isThrownBy(() -> writer.upsert("A", "new"));
        assertThat(Files.readString(file)).isEqualTo("A=old\n");
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).containsExactly(file);
        }
    }

    @Test
    void newSecretFileIsOwnerOnlyAndPermissionsSurviveReplacement() throws Exception {
        Path file = directory.resolve(".env");
        EnvFileWriter writer = new EnvFileWriter(file);
        writer.upsert("A", "secret");
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (posix != null) {
            assertThat(posix.readAttributes().permissions()).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            var before = posix.readAttributes().permissions();
            writer.upsert("A", "changed");
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(before);
        } else {
            assertThat(acl).isNotNull();
            var before = acl.getAcl();
            assertThat(before).allSatisfy(entry -> {
                assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                assertThat(entry.principal()).isEqualTo(acl.getOwner());
                assertThat(entry.permissions()).contains(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA);
            });
            writer.upsert("A", "changed");
            assertThat(Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl()).isEqualTo(before);
        }
    }

    @Test
    void directoryInsteadOfFileIsAnExplicitIoError() {
        EnvFileWriter writer = new EnvFileWriter(directory);
        assertThatIOException().isThrownBy(writer::read);
        assertThatIOException().isThrownBy(() -> writer.upsert("A", "value"));
    }
}
