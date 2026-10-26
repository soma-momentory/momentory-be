package com.momentory.retrospect.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 노션 욕구별 연결표의 배포 시점 스냅샷. 후보 선정과 화면 제목은 이 목록만 사용한다. */
public final class NeedActionCatalog {

    private final Snapshot snapshot;
    private final Map<String, Action> actions;
    private final Map<String, NeedMapping> mappings;

    public NeedActionCatalog(Snapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot);
        Map<String, Action> indexedActions = new LinkedHashMap<>();
        for (Action action : snapshot.actions()) {
            if (action.id() == null || action.id().isBlank()
                    || action.title() == null || action.title().isBlank()
                    || indexedActions.putIfAbsent(action.id(), action) != null) {
                throw new IllegalArgumentException("행동 ID·제목은 비어 있거나 중복될 수 없습니다.");
            }
        }
        Map<String, NeedMapping> indexedMappings = new LinkedHashMap<>();
        for (NeedMapping mapping : snapshot.needs()) {
            Need need = Needs.byWord(mapping.word())
                    .orElseThrow(() -> new IllegalArgumentException("목록 밖 욕구: " + mapping.word()));
            if (!need.meaning().equals(mapping.meaning())
                    || indexedMappings.putIfAbsent(mapping.word(), mapping) != null) {
                throw new IllegalArgumentException("욕구 정의가 다르거나 중복됩니다: " + mapping.word());
            }
            for (Link link : mapping.links()) {
                Action action = indexedActions.get(link.id());
                if (action == null || !action.title().equals(link.title())) {
                    throw new IllegalArgumentException("욕구 연결의 행동 ID·제목 불일치: " + link.id());
                }
            }
            if (mapping.links().stream().map(Link::id).distinct().count() != mapping.links().size()) {
                throw new IllegalArgumentException("중복 행동 연결: " + mapping.word());
            }
        }
        if (indexedMappings.size() != Needs.ALL.size()) {
            throw new IllegalArgumentException("모든 고정 욕구의 연결표가 필요합니다.");
        }
        actions = Collections.unmodifiableMap(indexedActions);
        mappings = Collections.unmodifiableMap(indexedMappings);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** 여러 욕구가 같은 행동을 가리켜도 한 번만 제시한다. 욕구 미확인 시 후보는 없다. */
    public List<Action> candidatesFor(List<Need> needs) {
        return needs.stream().map(Need::word).map(mappings::get).filter(Objects::nonNull)
                .flatMap(mapping -> mapping.links().stream()).map(Link::id).distinct()
                .map(actions::get).toList();
    }

    public String notesFor(List<Need> needs) {
        return needs.stream().map(Need::word).map(mappings::get).filter(Objects::nonNull)
                .map(mapping -> mapping.word() + ": " + mapping.note())
                .collect(Collectors.joining("\n"));
    }

    /** 모델이 보낸 번호는 허용된 연결과 대조하고, 문구는 모델 출력이 아닌 정본에서 가져온다. */
    public List<String> selectedTitles(List<Need> needs, List<String> selectedIds) {
        if (selectedIds == null) {
            return List.of();
        }
        Map<String, Action> allowed = candidatesFor(needs).stream()
                .collect(Collectors.toMap(Action::id, action -> action));
        return selectedIds.stream().filter(Objects::nonNull).map(String::strip)
                .map(allowed::get).filter(Objects::nonNull).distinct().limit(3)
                .map(Action::title).toList();
    }

    public record Snapshot(String sourcePage, String dictionaryPage, String snapshotDate,
            List<Action> actions, List<NeedMapping> needs) {
        public Snapshot {
            actions = List.copyOf(actions);
            needs = List.copyOf(needs);
        }
    }

    public record Action(String id, String title, String instructions, String researchDose,
            String limits, List<Source> sources) {
        public Action {
            sources = List.copyOf(sources);
        }
    }

    public record Source(String id, String citation, String url) {
    }

    public record NeedMapping(String word, String meaning, List<Link> links, String note) {
        public NeedMapping {
            links = List.copyOf(links);
        }
    }

    public record Link(String id, String title, String level, boolean preliminary) {
    }
}
