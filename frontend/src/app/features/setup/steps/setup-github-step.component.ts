import { Component, Input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GithubConfigRequest } from '../../../models/setup.model';
import { envName } from '../setup-validators';
import { SetupForm } from './setup-form';
function githubForm() {
  return new FormGroup({tokenKeyEnv: new FormControl<string | null>('PRJXP_HUB_GITHUB_TOKEN_KEY', envName),
    defaultPat: new FormControl<string | null>(null)});
}
@Component({selector: 'setup-github-step', standalone: true, imports: [ReactiveFormsModule],
  templateUrl: './setup-github-step.component.html', styleUrl: './setup-step.css'})
export class SetupGithubStepComponent extends SetupForm<GithubConfigRequest, ReturnType<typeof githubForm>['controls']> {
  @Input() githubEnabled = false;
  @Input() githubKeyAvailable = false;
  form = githubForm();
  private patched: GithubConfigRequest | null = null;
  protected patch(): void {
    if (this.initial === this.patched) return;
    this.patched = this.initial;
    this.form.patchValue({tokenKeyEnv: this.initial?.tokenKeyEnv ?? 'PRJXP_HUB_GITHUB_TOKEN_KEY',
      defaultPat: this.initial?.defaultPat ?? null}, {emitEvent: false});
  }
  configuration(): GithubConfigRequest { return this.form.getRawValue(); }
}
