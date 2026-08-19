package com.agentdrive.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexTaskPathsTest {
    @Test
    void trimsNormalizesAndDeduplicatesFileList() {
        assertThat(IndexTaskPaths.normalize(List.of(" docs\\a.txt ", "docs/a.txt", "notes.md")))
                .containsExactly("docs/a.txt", "notes.md");
    }

    @Test
    void rejectsTraversalAndInternalPaths() {
        assertThatThrownBy(() -> IndexTaskPaths.normalize(List.of("../secret.txt")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndexTaskPaths.normalize(List.of(".trash/old.txt")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dedupeKeyDoesNotDependOnFileOrder() {
        assertThat(IndexTaskPaths.dedupeKey(List.of("b.txt", "a.txt"), false))
                .isEqualTo(IndexTaskPaths.dedupeKey(List.of("a.txt", "b.txt"), false));
        assertThat(IndexTaskPaths.dedupeKey(List.of("a.txt"), false))
                .isNotEqualTo(IndexTaskPaths.dedupeKey(List.of("a.txt"), true));
    }
}
