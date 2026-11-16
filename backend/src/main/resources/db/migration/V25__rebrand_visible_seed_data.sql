UPDATE organizations
SET name = replace(name, 'Med-Node', 'Curavence')
WHERE name LIKE '%Med-Node%';

UPDATE studies
SET source_name = replace(source_name, 'Med-Node', 'Curavence')
WHERE source_name LIKE '%Med-Node%';
