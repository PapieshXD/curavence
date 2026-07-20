import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Auth } from '../../../../core/services/auth';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router, { optional: true });
  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly submitting = signal(false);
  protected readonly error = signal('');

  protected submit(): void {
    if (!this.username().trim() || !this.password()) {
      this.error.set('Wpisz login i hasło.');
      return;
    }
    this.submitting.set(true);
    this.error.set('');
    this.auth.login(this.username().trim(), this.password()).subscribe({
      next: () => this.router?.navigateByUrl('/'),
      error: (error) => {
        this.submitting.set(false);
        this.error.set(error?.error?.message ?? 'Nie udało się zalogować. Sprawdź Keycloak i backend.');
      },
    });
  }
}
