package com.momentory.letter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.domain.DiaryEvent;
import com.momentory.diary.infrastructure.DiaryEventCodec;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.retrospect.infrastructure.persistence.SafetyEventRepository;
import com.momentory.schedule.infrastructure.ScheduleRepository;
import com.momentory.user.infrastructure.UserProfileRepository;
import tools.jackson.databind.json.JsonMapper;

class LetterMaterialsReaderTest {
    @Test
    void readsWrittenDiaryEventsWithoutAChatSession() {
        var diaries = mock(DiaryRepository.class);
        var retrospects = mock(RetrospectRepository.class);
        var cards = mock(ActionCardRepository.class);
        var eventCodec = new DiaryEventCodec(JsonMapper.builder().build());
        Diary diary = Diary.write(7L, "친구와 산책하고 마음이 편안했다.", null);
        diary.replaceEvents(eventCodec.serialize(List.of(
                new DiaryEvent("친구와 산책", "친구와 걸으며 편안해졌다", List.of(Emotion.CALM)))));
        when(diaries.findById(31L)).thenReturn(Optional.of(diary));
        Letter letter = mock(Letter.class);
        when(letter.getDiaryId()).thenReturn(31L);
        when(letter.getUserId()).thenReturn(7L);
        when(letter.getLetterDate()).thenReturn(LocalDate.of(2026, 11, 1));
        var reader = new LetterMaterialsReader(mock(LetterRepository.class), mock(LetterActionRepository.class),
                diaries, retrospects, mock(RetrospectStateCodec.class), cards,
                mock(ScheduleRepository.class), mock(UserProfileRepository.class), mock(SafetyPolicy.class),
                mock(LetterWishCards.class), eventCodec, mock(SafetyEventRepository.class));

        var materials = reader.read(letter).orElseThrow();
        assertThat(materials.events()).containsExactly("친구와 산책: 친구와 걸으며 편안해졌다");
        assertThat(materials.diary()).isEqualTo(diary.getOriginal());
        verifyNoInteractions(retrospects, cards);
    }
}
