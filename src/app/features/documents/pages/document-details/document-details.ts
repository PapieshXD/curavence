import { Component } from '@angular/core';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';

@Component({
  selector: 'app-document-details',
  imports: [AppIcon, StatusText],
  templateUrl: './document-details.html',
  styleUrl: './document-details.scss',
})
export class DocumentDetails {}
