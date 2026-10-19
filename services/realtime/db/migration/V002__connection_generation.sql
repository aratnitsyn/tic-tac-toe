-- A logged sequence survives Redis loss and is shared by all socket pods.
CREATE SEQUENCE connection_generation AS bigint MINVALUE 1;
