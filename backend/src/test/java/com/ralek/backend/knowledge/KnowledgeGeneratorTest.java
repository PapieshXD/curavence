package com.ralek.backend.knowledge;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.ralek.backend.knowledge.KnowledgeDtos.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeGeneratorTest {
    final ObjectMapper mapper=new ObjectMapper();
    final List<Source> sources=List.of(new Source("a","DOCUMENT",UUID.randomUUID(),"Opis","Nie stwierdzono zmian.\nKontrola za rok.","v",""));
    @Test void acceptsFullNegativeSentence(){
        var result=KnowledgeGenerator.validate(mapper.readTree("[{\"kind\":\"FACT\",\"sourceId\":\"a\",\"quote\":\"Nie stwierdzono zmian.\"}]"),sources);
        assertEquals("Nie stwierdzono zmian.",result.getFirst().quote());
    }
    @Test void rejectsRemovedNegationAndFabricatedSources(){
        assertThrows(Exception.class,()->KnowledgeGenerator.validate(mapper.readTree("[{\"kind\":\"FACT\",\"sourceId\":\"a\",\"quote\":\"stwierdzono zmian.\"}]"),sources));
        assertThrows(Exception.class,()->KnowledgeGenerator.validate(mapper.readTree("[{\"kind\":\"FACT\",\"sourceId\":\"other\",\"quote\":\"Nie stwierdzono zmian.\"}]"),sources));
    }
    @Test void rejectsChangedNumbersAndUnknownKinds(){
        assertThrows(Exception.class,()->KnowledgeGenerator.validate(mapper.readTree("[{\"kind\":\"FACT\",\"sourceId\":\"a\",\"quote\":\"Kontrola za 2 lata.\"}]"),sources));
        assertThrows(Exception.class,()->KnowledgeGenerator.validate(mapper.readTree("[{\"kind\":\"DIAGNOSIS\",\"sourceId\":\"a\",\"quote\":\"Nie stwierdzono zmian.\"}]"),sources));
    }
    @Test void rejectsTruncatedDecimal(){
        var numbers=List.of(new Source("a","DOCUMENT",UUID.randomUUID(),"Opis","Wynik 14.2 g/dl.","v",""));
        assertThrows(Exception.class,()->KnowledgeGenerator.validate(mapper.readTree("[{\"kind\":\"FACT\",\"sourceId\":\"a\",\"quote\":\"Wynik 14.\"}]"),numbers));
    }
    @Test void missingInformationCanBeEmpty(){assertTrue(KnowledgeGenerator.validate(mapper.readTree("[]"),sources).isEmpty());}
}
