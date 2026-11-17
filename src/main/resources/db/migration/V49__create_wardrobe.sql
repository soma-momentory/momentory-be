-- 바바 꾸미기 · 귤. 아이템 그림은 앱 안에 있고, 서버는 값 · 판매 여부 · 가진 것 · 입은 것 · 귤만 둔다.
-- 아이템 id 는 「칸-아이디」(hat-cowboy)로 앱의 레이어 파일 이름과 같다. 모자와 옷에 같은 cowboy 가 있어서다.
CREATE TABLE wardrobe_items (
    id VARCHAR(40) PRIMARY KEY,
    slot VARCHAR(10) NOT NULL,
    tier VARCHAR(20) NOT NULL,
    price INT NOT NULL,
    on_sale BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_wardrobe_items_slot CHECK (slot IN ('HAT', 'EYES', 'CLOTHES')),
    CONSTRAINT chk_wardrobe_items_tier CHECK (tier IN ('FREE', 'TANGERINE')),
    CONSTRAINT chk_wardrobe_items_price CHECK ((tier = 'TANGERINE') = (price > 0))
);

-- 귤로 산 아이템만 남는다. 무료 아이템은 누구나 가진 것으로 본다.
CREATE TABLE user_wardrobe_items (
    user_id BIGINT NOT NULL,
    item_id VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, item_id),
    CONSTRAINT fk_user_wardrobe_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_wardrobe_items_item FOREIGN KEY (item_id) REFERENCES wardrobe_items (id)
);

CREATE TABLE user_outfits (
    user_id BIGINT PRIMARY KEY,
    hat_item_id VARCHAR(40),
    eyes_item_id VARCHAR(40),
    clothes_item_id VARCHAR(40),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_outfits_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_outfits_hat FOREIGN KEY (hat_item_id) REFERENCES wardrobe_items (id),
    CONSTRAINT fk_user_outfits_eyes FOREIGN KEY (eyes_item_id) REFERENCES wardrobe_items (id),
    CONSTRAINT fk_user_outfits_clothes FOREIGN KEY (clothes_item_id) REFERENCES wardrobe_items (id)
);

CREATE TABLE tangerine_wallets (
    user_id BIGINT PRIMARY KEY,
    balance INT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tangerine_wallets_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_tangerine_wallets_balance CHECK (balance >= 0)
);

-- 귤이 들고 난 기록. 보상은 (사람 · 사유 · 날짜)마다 한 번이고, 첫 선물(WELCOME)은 평생 한 번이다.
-- 구매는 reward_date 가 비어 있어 여러 번 남는다.
CREATE TABLE tangerine_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    reason VARCHAR(20) NOT NULL,
    amount INT NOT NULL,
    reward_date DATE,
    item_id VARCHAR(40),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tangerine_transactions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_tangerine_transactions_item FOREIGN KEY (item_id) REFERENCES wardrobe_items (id),
    CONSTRAINT chk_tangerine_transactions_reason
        CHECK (reason IN ('CHECK_IN', 'DIARY', 'SCHEDULES_DONE', 'REST', 'LETTER', 'WELCOME', 'PURCHASE')),
    CONSTRAINT uq_tangerine_transactions_reward UNIQUE (user_id, reason, reward_date)
);

CREATE UNIQUE INDEX uq_tangerine_transactions_welcome ON tangerine_transactions (user_id) WHERE reason = 'WELCOME';

-- 가격안(2026-10-15 확정)의 절반쯤: 얼굴 80 · 120, 모자 120 · 200, 옷 200 · 300. 무료 15 · 귤 38(합 7,040).
-- 과금이 없어 출석 + 일기만 해도 이틀에 하나쯤 사게 내렸다(2026-11-17 사용자). 구독 전용이던 6개는 구독을 만들지 않기로 해 귤로 판다.
INSERT INTO wardrobe_items (id, slot, tier, price) VALUES
    ('hat-purple-beanie', 'HAT', 'FREE', 0),
    ('hat-bucket', 'HAT', 'TANGERINE', 120),
    ('hat-cap', 'HAT', 'TANGERINE', 120),
    ('hat-washed-cap', 'HAT', 'TANGERINE', 120),
    ('hat-snapback', 'HAT', 'TANGERINE', 120),
    ('hat-fisherman-beanie', 'HAT', 'TANGERINE', 120),
    ('hat-beret', 'HAT', 'TANGERINE', 200),
    ('hat-fedora', 'HAT', 'TANGERINE', 200),
    ('hat-panama', 'HAT', 'TANGERINE', 200),
    ('hat-hunting-cap', 'HAT', 'TANGERINE', 200),
    ('hat-sun-visor', 'HAT', 'TANGERINE', 120),
    ('hat-trapper', 'HAT', 'TANGERINE', 200),
    ('hat-cowboy', 'HAT', 'TANGERINE', 200),
    ('hat-chef-hat', 'HAT', 'TANGERINE', 200),
    ('hat-headset', 'HAT', 'TANGERINE', 200),
    ('hat-sheep-towel', 'HAT', 'FREE', 0),
    ('hat-chick', 'HAT', 'TANGERINE', 200),
    ('hat-straw-hat', 'HAT', 'TANGERINE', 200),
    ('eyes-sunglasses', 'EYES', 'TANGERINE', 80),
    ('eyes-glasses', 'EYES', 'FREE', 0),
    ('eyes-color-sunglasses', 'EYES', 'TANGERINE', 80),
    ('eyes-pixel-sunglasses', 'EYES', 'TANGERINE', 120),
    ('eyes-pinocchio-nose', 'EYES', 'TANGERINE', 120),
    ('eyes-face-frown', 'EYES', 'FREE', 0),
    ('eyes-face-sad', 'EYES', 'FREE', 0),
    ('eyes-face-smile', 'EYES', 'FREE', 0),
    ('eyes-face-surprised', 'EYES', 'FREE', 0),
    ('eyes-3d-glasses', 'EYES', 'TANGERINE', 120),
    ('eyes-alien-glasses', 'EYES', 'TANGERINE', 120),
    ('eyes-face-sleepy', 'EYES', 'FREE', 0),
    ('eyes-face-calm', 'EYES', 'FREE', 0),
    ('eyes-face-heart', 'EYES', 'FREE', 0),
    ('eyes-face-star', 'EYES', 'FREE', 0),
    ('eyes-face-dizzy', 'EYES', 'FREE', 0),
    ('eyes-face-dot', 'EYES', 'FREE', 0),
    ('eyes-mustache', 'EYES', 'TANGERINE', 80),
    ('eyes-bandage', 'EYES', 'TANGERINE', 80),
    ('eyes-ladybug', 'EYES', 'TANGERINE', 120),
    ('clothes-knit-vest', 'CLOTHES', 'FREE', 0),
    ('clothes-overalls', 'CLOTHES', 'TANGERINE', 300),
    ('clothes-pajamas', 'CLOTHES', 'TANGERINE', 300),
    ('clothes-gray-sweater', 'CLOTHES', 'FREE', 0),
    ('clothes-marine-tee', 'CLOTHES', 'TANGERINE', 200),
    ('clothes-cable-sweater', 'CLOTHES', 'TANGERINE', 200),
    ('clothes-tracksuit', 'CLOTHES', 'TANGERINE', 200),
    ('clothes-tracksuit-black', 'CLOTHES', 'TANGERINE', 200),
    ('clothes-light-padding', 'CLOTHES', 'TANGERINE', 200),
    ('clothes-fleece-pajamas', 'CLOTHES', 'TANGERINE', 300),
    ('clothes-chef-coat', 'CLOTHES', 'TANGERINE', 300),
    ('clothes-soccer-kit', 'CLOTHES', 'TANGERINE', 300),
    ('clothes-cowboy', 'CLOTHES', 'TANGERINE', 300),
    ('clothes-painter-smock', 'CLOTHES', 'TANGERINE', 300),
    ('clothes-yukata', 'CLOTHES', 'TANGERINE', 300);
