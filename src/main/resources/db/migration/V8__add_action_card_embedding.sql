-- 행동 카드의 '상황'을 의미 벡터로 저장한다 — "비슷한 상황에서 만든 이전 카드" 검색용.
-- pgvector 는 V1 에서 이미 켰다. text-embedding-004 = 768차원.
ALTER TABLE action_cards ADD COLUMN situation_embedding vector(768);

-- 코사인 거리(<=>) 최근접 검색용 HNSW 인덱스. 사용자별로 좁혀 쓰지만 벡터 정렬은 이 인덱스가 받친다.
CREATE INDEX idx_action_cards_situation_embedding
    ON action_cards USING hnsw (situation_embedding vector_cosine_ops);
