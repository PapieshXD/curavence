import { Injectable, inject, signal } from '@angular/core';
import { Api } from './api';

@Injectable({providedIn:'root'})
export class Readiness {
  private readonly api=inject(Api);
  readonly config=signal({searchEnabled:false,exportsEnabled:false});
  constructor(){this.api.libraryConfig().subscribe({next:config=>this.config.set(config),error:()=>{}});}
}
