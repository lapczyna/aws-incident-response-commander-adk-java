-- Creates the separate database the demo target service uses.
--
-- Kept apart from the commander database on purpose: the target service is the subject of an
-- incident, and letting it share storage with the system investigating it would make the
-- connection-pool-pressure scenario affect the investigator as well as the investigated.
CREATE USER demotarget WITH PASSWORD 'demotarget';
CREATE DATABASE demotarget OWNER demotarget;
GRANT ALL PRIVILEGES ON DATABASE demotarget TO demotarget;
