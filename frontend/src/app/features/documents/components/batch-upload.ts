import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpEventType } from '@angular/common/http';
import { lastValueFrom } from 'rxjs';
import { DocumentService } from '../services/document';
import { WorkspaceStore } from '../../../core/services/workspace-store';

interface UploadItem { file: File; title: string; state: 'ready' | 'uploading' | 'done' | 'error'; error: string; }
@Component({
  selector:'app-batch-upload', imports:[FormsModule],
  template:`<details class="batch"><summary>Dodaj kilka dokumentów PDF naraz</summary>
    <p>Każdy plik zostanie osobnym dokumentem. Nazwy możesz poprawić przed zapisem. Maksymalnie 20 plików, po 25 MB.</p>
    @if (workspace.role() === 'patient') {
      <input type="file" multiple accept=".pdf,application/pdf" aria-label="Wybierz wiele dokumentów PDF" [disabled]="busy()" (change)="choose($event)" />
      @if (error()) { <p role="alert">{{ error() }}</p> }
      @for (item of items(); track item) { <div class="row"><label>{{ item.file.name }}<input [(ngModel)]="item.title" maxlength="255" aria-label="Nazwa dokumentu" [disabled]="busy() || item.state === 'done'" /></label><span role="status">{{ label(item.state) }} {{ item.error }}</span></div> }
      <button type="button" class="primary-button" [disabled]="busy() || !hasPending()" (click)="upload()">{{ busy() ? 'Zapisywanie…' : 'Zapisz niezapisane dokumenty' }}</button>
    } @else { <p>Dodawanie wielu dokumentów jest dostępne w widoku pacjenta.</p> }
  </details>`,
  styles:[`.batch{background:white;border:1px solid #dbe5e4;border-radius:12px;padding:20px;margin:20px 0}summary{cursor:pointer;font-weight:600}.row{padding:12px 0;display:grid;gap:8px}label{display:grid;gap:6px}input{padding:9px;max-width:100%;box-sizing:border-box}button{margin-top:12px}`],
})
export class BatchUpload {
  readonly workspace=inject(WorkspaceStore);
  private readonly documents=inject(DocumentService);
  readonly items=signal<UploadItem[]>([]); readonly busy=signal(false); readonly error=signal('');
  hasPending():boolean { return this.items().some(i => i.state !== 'done'); }
  label(state:UploadItem['state']):string { return {ready:'Do zapisania',uploading:'Zapisywanie',done:'Zapisano',error:'Nie zapisano'}[state]; }
  choose(event:Event):void {
    if(this.busy()) return;
    const files=Array.from((event.target as HTMLInputElement).files ?? []);
    this.error.set('');
    if(files.length>20 || files.some(f=> !f.name.toLowerCase().endsWith('.pdf') || f.size>25*1024*1024 || !f.size)) {
      this.error.set('Wybierz do 20 niepustych plików PDF o rozmiarze do 25 MB każdy.'); return;
    }
    this.items.set(files.map(file=>({file,title:file.name.replace(/\.pdf$/i,''),state:'ready',error:''})));
  }
  async upload():Promise<void> {
    if(this.busy() || this.workspace.role()!=='patient') return;
    if(this.items().some(i=>i.state!=='done' && !i.title.trim())) {this.error.set('Uzupełnij nazwy dokumentów.');return;}
    this.busy.set(true);this.error.set('');
    for(const item of this.items().filter(i=>i.state!=='done')) {
      item.state='uploading';item.error='';this.items.update(items=>[...items]);
      try {
        const result=await lastValueFrom(this.documents.upload('patient',item.file,item.title));
        if(result.type!==HttpEventType.Response) throw new Error('Brak potwierdzenia zapisu');
        item.state='done';
      } catch {item.state='error';item.error='Spróbuj ponownie. Zapisane pliki zostaną pominięte.';}
      this.items.update(items=>[...items]);
    }
    this.busy.set(false);this.workspace.load();
  }
}
