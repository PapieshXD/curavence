import { Component, input } from '@angular/core';
import { StatusTone } from '../../../core/models/workspace';
import { AppIcon } from '../app-icon/app-icon';

@Component({
  selector: 'app-status-text',
  imports: [AppIcon],
  template: `
    <span class="status-text" [class]="'status-text ' + tone()">
      @if (icon()) {
        <app-icon [name]="icon()" [size]="13" />
      } @else {
        <span class="status-dot" aria-hidden="true"></span>
      }
      <ng-content />
    </span>
  `,
})
export class StatusText {
  readonly tone = input<StatusTone>('neutral');
  readonly icon = input('');
}
