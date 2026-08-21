CREATE TABLE agent_skills (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name text NOT NULL,
    description text NOT NULL,
    instructions text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_skills_user_name_unique UNIQUE (user_id, name),
    CONSTRAINT agent_skills_name_format CHECK (
        char_length(name) BETWEEN 1 AND 64
        AND name ~ '^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$'
    ),
    CONSTRAINT agent_skills_description_length CHECK (char_length(description) BETWEEN 1 AND 500),
    CONSTRAINT agent_skills_instructions_length CHECK (char_length(instructions) BETWEEN 1 AND 16000),
    CONSTRAINT agent_skills_version_positive CHECK (version >= 1)
);

CREATE INDEX agent_skills_user_enabled_name_idx
    ON agent_skills(user_id, enabled, name);
