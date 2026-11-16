import { Component, input } from '@angular/core';

@Component({
  selector: 'app-icon',
  templateUrl: './app-icon.html',
  styleUrl: './app-icon.scss',
})
export class AppIcon {
  readonly name = input.required<string>();
  readonly size = input(18);
}
