-- SmartBox fixed local demo database. Public demo credentials; do not use in production.
CREATE DATABASE IF NOT EXISTS smartbox_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'smartbox_app'@'127.0.0.1' IDENTIFIED BY '33c529b043d49f93ec47f846ba6de8f3e4b206d727975659';
CREATE USER IF NOT EXISTS 'smartbox_migrator'@'127.0.0.1' IDENTIFIED BY '927c9334a5fb8245fcc9da358b1f7fc8cd239cc15e089522';
ALTER USER 'smartbox_app'@'127.0.0.1' IDENTIFIED BY '33c529b043d49f93ec47f846ba6de8f3e4b206d727975659';
ALTER USER 'smartbox_migrator'@'127.0.0.1' IDENTIFIED BY '927c9334a5fb8245fcc9da358b1f7fc8cd239cc15e089522';
GRANT SELECT, INSERT, UPDATE, DELETE ON smartbox_dev.* TO 'smartbox_app'@'127.0.0.1';
GRANT ALL PRIVILEGES ON smartbox_dev.* TO 'smartbox_migrator'@'127.0.0.1';
FLUSH PRIVILEGES;
