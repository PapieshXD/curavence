import { Component, inject } from '@angular/core';
import { Realtime } from './core/services/realtime';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App { private readonly realtime = inject(Realtime); }
