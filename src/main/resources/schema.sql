CREATE TABLE IF NOT EXISTS transcriptions (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    title            VARCHAR(200) NOT NULL,
    transcript       TEXT         NOT NULL,
    language         VARCHAR(20),
    duration_seconds INT,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL
);