package com.momentory.retrospect.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.momentory.retrospect.NeedActionCatalogFixture;

class NeedActionCatalogTest {

    private final NeedActionCatalog catalog = NeedActionCatalogFixture.load();

    @Test
    void snapshotPreservesApprovedNotionDataAndExclusions() {
        var snapshot = catalog.snapshot();
        assertThat(snapshot.sourcePage()).endsWith("405b5a896e428198ab9adb01dafd2285");
        assertThat(snapshot.snapshotDate()).isEqualTo("2026-10-26");
        assertThat(snapshot.actions()).hasSize(55);
        assertThat(snapshot.needs()).hasSize(50);
        assertThat(snapshot.needs().stream().mapToInt(n -> n.links().size()).sum()).isEqualTo(195);
        assertThat(snapshot.actions()).extracting(NeedActionCatalog.Action::id)
                .doesNotHaveDuplicates().doesNotContain("A05", "A14", "A22", "A33", "A53", "A57", "A60");
        assertThat(snapshot.actions()).allSatisfy(action -> {
            assertThat(action.instructions()).isNotBlank();
            assertThat(action.researchDose()).isNotBlank();
            assertThat(action.limits()).isNotBlank();
            assertThat(action.sources()).isNotEmpty().allSatisfy(source ->
                    assertThat(URI.create(source.url()).getScheme()).isEqualTo("https"));
        });
        assertThat(catalog.candidatesFor(List.of(need("평안"))))
                .extracting(NeedActionCatalog.Action::title)
                .contains("20분 동안 마음이 편한 장소 구체적으로(풍경, 소리, 촉감) 상상하기");
    }

    @Test
    void onlyActionsLinkedToSelectedNeedsAreEligible() {
        assertThat(catalog.candidatesFor(List.of(need("정의"))))
                .extracting(NeedActionCatalog.Action::id).containsExactly("A35", "A59");
        assertThat(catalog.candidatesFor(List.of(need("부"))))
                .extracting(NeedActionCatalog.Action::id).containsExactly("A56", "A58");
        assertThat(catalog.notesFor(List.of(need("정의")))).contains("불공정을 시정", "간접 참고");
        assertThat(catalog.candidatesFor(List.of())).isEmpty();
        assertThat(catalog.candidatesFor(List.of(new Need("목록 밖 바람", "사용자 입력")))).isEmpty();
    }

    @Test
    void unmappedActionsAreNotAddedToAnyNeed() {
        assertThat(catalog.candidatesFor(Needs.ALL)).extracting(NeedActionCatalog.Action::id)
                .hasSize(51).doesNotHaveDuplicates().doesNotContain("A10", "A20", "A27", "A31");
    }

    @Test
    void validatesModelIdsAndUsesExactApprovedTitles() {
        assertThat(catalog.selectedTitles(List.of(need("정의")), Arrays.asList(
                null, "A35", "A01", "A33", "A53", "A57", "A35", " A59 ", "새 행동 만들기")))
                .containsExactly("상대에게 ‘그때 어떤 뜻이었어?’라고 직접 묻기",
                        "믿는 사람에게 내 생각을 솔직하게 말하기");
        assertThat(catalog.selectedTitles(List.of(need("정의")), null)).isEmpty();
    }

    @Test
    void preservesModelRankingButLimitsToThreeDifferentActions() {
        assertThat(catalog.selectedTitles(List.of(need("평안")),
                List.of("A07", "A03", "A07", "A02", "A01", "A04")))
                .containsExactly("원할 때, 알림·대화를 멈추고 15분 혼자 있기",
                        "20분 동안 마음이 편한 장소 구체적으로(풍경, 소리, 촉감) 상상하기",
                        "20분 동안 근육에 힘을 줬다가 천천히 풀기");
    }

    @Test
    void missingReferenceCannotSilentlyBroadenOrBreakRecommendations() {
        var snapshot = catalog.snapshot();
        var mappings = new ArrayList<>(snapshot.needs());
        var old = mappings.getFirst();
        mappings.set(0, new NeedActionCatalog.NeedMapping(old.word(), old.meaning(),
                List.of(new NeedActionCatalog.Link("A99", "목록 밖 행동", "해석", false)), old.note()));

        assertThatThrownBy(() -> new NeedActionCatalog(new NeedActionCatalog.Snapshot(
                snapshot.sourcePage(), snapshot.dictionaryPage(), snapshot.snapshotDate(),
                snapshot.actions(), mappings)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A99");
    }

    private static Need need(String word) {
        return Needs.byWord(word).orElseThrow();
    }
}
