-- MySQL schema (also accepted by H2 in MySQL mode, used by the tests)
CREATE TABLE IF NOT EXISTS users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(100) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS queues (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  name VARCHAR(60) NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT fk_queue_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- status: waiting | serving | served | cancelled
-- pos = order inside the queue, token_no = the number printed on the token
CREATE TABLE IF NOT EXISTS tokens (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  queue_id BIGINT NOT NULL,
  token_no INT NOT NULL,
  name VARCHAR(60) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'waiting',
  pos INT NOT NULL,
  created_at BIGINT NOT NULL,
  started_at BIGINT,
  completed_at BIGINT,
  cancelled_at BIGINT,
  CONSTRAINT fk_token_queue FOREIGN KEY (queue_id) REFERENCES queues(id) ON DELETE CASCADE,
  CONSTRAINT uq_token_no UNIQUE (queue_id, token_no)
);
