CREATE TABLE sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  language TEXT NOT NULL CHECK (language IN ('DE','EN')),
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','STOPPING','COMPLETED','FAILED')),
  asr_model TEXT NOT NULL,
  retention_minutes INTEGER NOT NULL,
  chunk_max_sec INTEGER NOT NULL,
  vad_silence_ms INTEGER NOT NULL,
  export_uri TEXT
);

CREATE TABLE audio_chunks (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  started_at_offset_ms INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL,
  audio_blob BLOB,                       -- WAV bytes; present CAPTURED→terminal; [REFINES Doc 1 §5 uploadBytes]
  retained_path TEXT,                    -- set when moved to AudioRetentionStore file
  state TEXT NOT NULL CHECK (state IN
    ('CAPTURED','ASR_PENDING','ASR_DONE','ANALYZED','DISCARDED','FAILED')),
  transcript TEXT,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  UNIQUE (session_id, seq)
);
CREATE INDEX idx_chunks_queue ON audio_chunks(session_id, state, seq);

CREATE TABLE statements (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,                  -- per-session monotonic
  speaker_tag TEXT,                      -- inferred, e.g. 'S2'; never overwritten (D-5)
  text TEXT NOT NULL,
  started_at_offset_ms INTEGER NOT NULL,
  ended_at_offset_ms INTEGER,
  state TEXT NOT NULL CHECK (state IN ('PROVISIONAL','FINAL')),
  source_chunk_ids TEXT NOT NULL,        -- JSON int array
  UNIQUE (session_id, seq)
);

CREATE TABLE annotations (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  statement_id INTEGER NOT NULL REFERENCES statements(id) ON DELETE CASCADE,
  family TEXT NOT NULL CHECK (family IN ('FACT_CHECK','LOGIC_RHETORIC')),
  sub_type TEXT NOT NULL,
  note TEXT NOT NULL,
  quote TEXT,
  created_at_offset_ms INTEGER NOT NULL
);
CREATE INDEX idx_annotations_stmt ON annotations(statement_id);

CREATE TABLE claims (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  statement_id INTEGER NOT NULL REFERENCES statements(id) ON DELETE CASCADE,
  compact_text TEXT NOT NULL,
  created_at_offset_ms INTEGER NOT NULL
);

CREATE TABLE speaker_mappings (
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  raw_tag TEXT NOT NULL,
  display_name TEXT,
  merged_into_tag TEXT,
  PRIMARY KEY (session_id, raw_tag)
);

CREATE TABLE analysis_jobs (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  chunk_seq INTEGER,                     -- null for REANALYSIS
  target_statement_ids TEXT NOT NULL,    -- JSON long array; text loaded fresh at run time
  reason TEXT NOT NULL CHECK (reason IN ('CHUNK','REANALYSIS')),
  state TEXT NOT NULL CHECK (state IN ('PENDING','IN_FLIGHT','DONE','FAILED')),
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT
);
CREATE INDEX idx_jobs_queue ON analysis_jobs(session_id, state, id);
