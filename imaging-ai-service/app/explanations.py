"""Deterministic audience explanations: no second interpretation of the images."""
import hashlib
import json


DISCLAIMER = "Wynik automatyczny nie jest diagnozą i wymaga oceny wykwalifikowanego specjalisty."


def evidence_hash(evidence: dict) -> str:
    return hashlib.sha256(json.dumps(evidence, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def explain(evidence: dict, completed: int, eligible: int) -> dict:
    count = len(evidence["observations"]) + len(evidence["candidateFindings"])
    seen = (f"Model wskazał {count} obszarów do oceny przez specjalistę." if count else
            "Model nie zgłosił obszarów wymagających uwagi. Nie wyklucza to choroby.")
    if completed == 0:
        seen = "Nie udało się ocenić obrazów. Nie ma wyniku pozwalającego wnioskować o zmianach."
    coverage = f"Przetworzono {completed} z {eligible} zakwalifikowanych grup obrazów."
    limitations = [DISCLAIMER, coverage]
    if evidence["quality"]["status"] != "acceptable":
        limitations.append("Jakość obrazów wymaga sprawdzenia; wynik może być niepełny.")
    return {
        "PATIENT": {
            "headline": "Co pokazuje badanie?", "whatWasSeen": seen,
            "whatItMayMean": "Znaczenie wyniku oceni lekarz w kontekście objawów i pozostałych badań.",
            "whatItDoesNotMean": "Samo oznaczenie kolorem nie oznacza rozpoznania choroby.",
            "nextStep": "Omów wynik z lekarzem.",
            "questionsForDoctor": ["Czy wynik wyjaśnia moje objawy?",
                                   "Czy warto porównać obrazy z wcześniejszym badaniem?"],
            "limitations": limitations, "disclaimer": DISCLAIMER,
        },
        "PHYSIOTHERAPY": {
            "clinicalContext": seen, "functionalRelevance":
                "Sam obraz nie określa sprawności ani dopuszczalnego obciążenia. Potrzebna jest ocena funkcjonalna.",
            "precautions": ["Ustal ograniczenia z prowadzącym lekarzem i planem terapii."],
            "measurements": evidence["measurements"],
            "recommendedQuestions": ["Czy lekarz ustalił przeciwwskazania do obciążenia?"],
            "limitations": limitations,
        },
        "CLINICIAN": {
            "technicalSummary": coverage, "observations": evidence["observations"],
            "candidateFindings": evidence["candidateFindings"], "measurements": evidence["measurements"],
            "differentialConsiderations": [], "comparisonToPrior": [],
            "limitations": limitations, "requiresHumanReview": True,
        },
    }
