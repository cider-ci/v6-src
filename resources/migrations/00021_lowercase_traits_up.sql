UPDATE executors
   SET traits = ARRAY(SELECT lower(t) FROM unnest(traits) t);
