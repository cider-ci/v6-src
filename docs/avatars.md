# Avatars

People are shown as images, not names: the signed-in user in the navbar,
the users list and user page, the owner of the key a commit is signed
with (commit, job and task pages, the executor's accepted keys), and the
author and committer of a commit (commits, branch and commit pages). The
name, login and email address are in the tooltip and the alt text.

## Where the image comes from

- `/users/<id>/avatar`: the user's stored image (table `user_images`,
  imported at each [sign-in with GitHub](github-sign-in.md)), else an
  **identicon** generated from the MD5 of the lower-cased primary email
  address (the oldest address when none is primary, the user id when the
  user has no address).
- `/avatars/by-email/<md5 of the lower-cased address>`: the avatar of the
  user who owns the address (as above), else an identicon of the hash.
  Commit authors and committers are shown this way, with or without an
  account, and a person's commits get the same image as the account.

Both need a signed-in user and are cached by the browser for an hour
(`ETag` revalidation); a new image shows up at the latest an hour after
the sign-in that imported it. Nothing is loaded from GitHub by the browser.

## Identicons

The identicons look like GitHub's: a 5x5 sprite mirrored left to right,
in one colour on light grey, as reproduced by
[identicon.js](https://github.com/stewartlord/identicon.js) from the MD5
hash (chars 0-14 decide the pixels of the middle, inner and outer columns
by even/odd nibble; the hue is the last 7 chars over `0xFFFFFFF`, with
saturation 0.7 and lightness 0.5). GitHub hashes its internal user id, so
an identicon here never equals GitHub's for the same person; GitHub users
get their real GitHub image anyway, which for users without an uploaded
picture is GitHub's identicon. See `cider-ci.utils.identicon`.

Users who sign in with a password only have no way to upload an image;
they keep the identicon.
