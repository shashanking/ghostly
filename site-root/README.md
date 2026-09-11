# Files that must be served from the **root** of the developer website

This folder is not part of the app and not part of the privacy-policy site. It holds the one file
that has to sit at the very top of the domain Google Play lists as the developer website.

## Why it is not in `docs/`

`docs/` is the master copy of the **ghostly-privacy** repo, which GitHub Pages publishes as a
*project* page:

```
https://shashanking.github.io/ghostly-privacy/privacy-policy.html
```

A project page lives under a path. `app-ads.txt` is fetched from the **root of the domain**, not
from the path in the store listing — so a copy at
`shashanking.github.io/ghostly-privacy/app-ads.txt` is never looked at, and AdMob reports the app
as unverified without ever saying why. This is the single most common way this goes wrong.

## Where it actually has to go

AdMob takes the host out of the **Website** field of the Play store listing and fetches
`https://<host>/app-ads.txt`. With the current listing that host is `shashanking.github.io`, so the
file must end up at:

```
https://shashanking.github.io/app-ads.txt
```

On GitHub Pages, the root of `<user>.github.io` is served by a repository **named exactly**
`shashanking.github.io` — a *user* page, not a project page. So:

1. Create a public repo called `shashanking.github.io`.
2. Put `app-ads.txt` from this folder in its root. Nothing else is needed.
3. Enable Pages on it (branch `main`, folder `/`).
4. Check it with `curl -i https://shashanking.github.io/app-ads.txt`. Two things must be true: a
   `200`, and `content-type: text/plain`. If it comes back as `text/html` the crawler treats the
   response as a web page and gives up. GitHub Pages serves `.txt` as `text/plain`, so this is a
   check rather than a worry.

## The Play Console side

The **Website** field in the store listing is what AdMob reads, and `play/store-listing.md`
currently marks it *optional*. It is not optional any more — if it is blank there is no domain to
crawl and verification cannot succeed. Set it to `https://shashanking.github.io` (the root, so the
listing and the crawl target agree) before asking AdMob to verify.

Verification is a crawl, not an instant check: allow up to 24 hours before treating a failure as
real.

## If you ever buy a real domain

Move the file to that domain's root instead, change the Play listing's Website field to match, and
delete this folder. A domain you own is the more durable answer — `github.io` is shared hosting,
and the account that owns the name is the only thing tying it to you.

## What the line means

```
google.com, pub-7003323211605669, DIRECT, f08c47fec0942fa0
```

Read as: Google is authorised to sell this app's ad inventory, directly, for publisher account
`pub-7003323211605669`; the last field is Google's own certification authority id and is the same
for everyone. The publisher id is not a secret — it goes out with every ad request — so this file
is safe in version control.
