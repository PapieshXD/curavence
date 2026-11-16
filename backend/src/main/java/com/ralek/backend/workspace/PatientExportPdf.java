package com.ralek.backend.workspace;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Plain text only. No HTML, external images, links, or model-generated content. */
final class PatientExportPdf implements AutoCloseable {
    private final PDDocument document=new PDDocument();
    private final PDType0Font font;
    private PDPageContentStream stream; private float y;
    private static final float MARGIN=48,WIDTH=PDRectangle.A4.getWidth()-2*MARGIN;
    private PatientExportPdf() throws IOException {
        try(var input=PatientExportPdf.class.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
            if(input==null)throw new IOException("Missing bundled font");font=PDType0Font.load(document,input);
        }
        page();
    }
    static byte[] render(PatientExportService.Snapshot snapshot) throws IOException {
        try(var pdf=new PatientExportPdf();var bytes=new ByteArrayOutputStream()) {
            pdf.paragraph("Curavence - pakiet na wizytę",19);
            pdf.paragraph("Pacjent: "+snapshot.patientName(),12);
            pdf.paragraph("Wygenerowano: "+DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(ZonedDateTime.now(ZoneId.of("Europe/Warsaw"))),10);
            pdf.paragraph("Wybrane informacje pacjenta i zatwierdzone ustalenia lekarza. Oryginały dokumentów pozostają materiałem źródłowym.",10);
            for(String block:snapshot.blocks())pdf.paragraph(block,11);
            pdf.paragraph("WYBRANE DOKUMENTY",14);
            if(snapshot.documents().isEmpty())pdf.paragraph("Nie wybrano dokumentów.",11);
            int i=0;
            for(var d:snapshot.documents()) {
                pdf.paragraph(++i+". "+d.title(),12);
                pdf.paragraph("Data badania: "+(d.performedAt()==null?"Nie podano":d.performedAt().substring(0,10))+"; oryginalna nazwa: "+(d.originalFilename()==null?"Brak oryginału":d.originalFilename()),10);
            }
            pdf.paragraph("INFORMACJE TECHNICZNE",13);
            pdf.paragraph("Dokument przedstawia wybrany zakres na moment przygotowania. Deklaracja wykonania zadania przez pacjenta nie oznacza oceny wyniku przez lekarza. Przekazanej kopii nie można wycofać przez cofnięcie zgody w Curavence.",10);
            pdf.stream.close();pdf.stream=null;
            int count=pdf.document.getNumberOfPages();
            for(i=0;i<count;i++)try(var footer=new PDPageContentStream(pdf.document,pdf.document.getPage(i),PDPageContentStream.AppendMode.APPEND,true,true)) {
                footer.beginText();footer.setFont(pdf.font,9);footer.newLineAtOffset(MARGIN,27);footer.showText("Curavence | "+(i+1)+" / "+count);footer.endText();
            }
            pdf.document.save(bytes);return bytes.toByteArray();
        }
    }
    private void page() throws IOException {
        if(stream!=null)stream.close();var page=new PDPage(PDRectangle.A4);document.addPage(page);
        stream=new PDPageContentStream(document,page);y=PDRectangle.A4.getHeight()-MARGIN;
    }
    private String printable(String text) throws IOException {
        var result=new StringBuilder();
        for(int cp:text.codePoints().toArray()) {
            if(cp=='\n' || cp=='\r'){result.append('\n');continue;}
            if(cp=='\t'){result.append("    ");continue;}
            if(Character.isISOControl(cp))continue;
            result.appendCodePoint(font.hasGlyph(cp)?cp:'?');
        }
        return result.toString();
    }
    private void paragraph(String text,float size) throws IOException {
        for(String source:printable(text).split("\\R",-1)) {
            var line=new StringBuilder();
            for(String word:source.split(" ",-1)) {
                String next=line.isEmpty()?word:line+" "+word;
                if(font.getStringWidth(next)*size/1000>WIDTH && !line.isEmpty()){line(line.toString(),size);line.setLength(0);}
                if(!line.isEmpty())line.append(' ');
                for(int cp:word.codePoints().toArray()) {
                    next=line.toString()+new String(Character.toChars(cp));
                    if(font.getStringWidth(next)*size/1000>WIDTH && !line.isEmpty()){line(line.toString(),size);line.setLength(0);}
                    line.appendCodePoint(cp);
                }
            }
            line(line.toString(),size);
        }
        y-=8;
    }
    private void line(String text,float size) throws IOException {
        if(y-size*1.45f<MARGIN)page();
        stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(MARGIN,y);stream.showText(text);stream.endText();y-=size*1.45f;
    }
    public void close() throws IOException {if(stream!=null)stream.close();document.close();}
}
