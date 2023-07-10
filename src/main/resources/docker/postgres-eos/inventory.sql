-- remove the table first if exists
DROP TABLE IF EXISTS public.eos_test;
CREATE TABLE public.eos_test(id SERIAL NOT NULL PRIMARY KEY, val SERIAL, t TIMESTAMP DEFAULT now());

