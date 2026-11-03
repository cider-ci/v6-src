require 'spec_helper'
require 'digest'

# Avatars are shown in place of names: a user's imported image (see
# github_sign_in_spec), else an identicon generated from the primary email
# address; commit authors and committers by the md5 of their address.
feature 'Avatars' do

  before :each do
    @admin = FactoryBot.create(:admin)
    @user  = FactoryBot.create(:user)
    set_session_cookie @admin
  end

  def email_hash(email)
    Digest::MD5.hexdigest(email.downcase)
  end

  # every avatar on the page is a loaded image (not its alt text)
  def expect_avatars_loaded(count)
    expect(page).to have_css 'img.avatar', count: count
    wait_until(10) do
      page.evaluate_script('Array.from(document.querySelectorAll("img.avatar"))' \
                           '.every(i => i.complete && i.naturalWidth > 0)')
    end
  end

  scenario 'a user without an image gets an identicon from the primary email address' do
    res = http_get("/users/#{@user.id}/avatar")
    expect(res.code).to eq '200'
    expect(res['content-type']).to start_with 'image/svg+xml'
    expect(res['etag']).to be_present
    expect(res.body).to start_with '<svg'

    # the same image for the address, and for the same address written differently
    by_email = http_get("/avatars/by-email/#{email_hash(@user.email_address)}")
    expect(by_email.body).to eq res.body
    expect(http_get("/avatars/by-email/#{email_hash(@user.email_address.upcase)}").body).to eq res.body

    # a non-primary address of the user gives the user's identicon too
    other = database[:email_addresses].where(user_id: @user.id, is_primary: false).first[:email_address]
    expect(http_get("/avatars/by-email/#{email_hash(other)}").body).to eq res.body

    # the browser revalidates with the ETag
    expect(http_get("/users/#{@user.id}/avatar", headers: { 'If-None-Match' => res['etag'] }).code).to eq '304'
  end

  scenario 'an address without an account gets its own identicon; bad requests are refused' do
    a = http_get("/avatars/by-email/#{email_hash('nobody@example.org')}")
    b = http_get("/avatars/by-email/#{email_hash('somebody@example.org')}")
    expect(a.code).to eq '200'
    expect(a['content-type']).to start_with 'image/svg+xml'
    expect(a.body).not_to eq b.body
    expect(a.body).to eq http_get("/avatars/by-email/#{email_hash('nobody@example.org')}").body

    expect(http_get('/avatars/by-email/not-a-hash').code).to eq '404'
    expect(http_get('/users/not-a-uuid/avatar').code).to eq '404'
    expect(http_get("/users/#{SecureRandom.uuid}/avatar").code).to eq '404'
    expect(http_get("/users/#{@user.id}/avatar", cookie: 'cider-ci-session=bogus').code).to eq '403'
  end

  scenario 'a stored image is served instead of the identicon' do
    database[:user_images].insert(user_id: @user.id, content_type: 'image/png',
                                  data: Sequel.blob(FAKE_AVATAR_PNG), source: 'github')
    res = http_get("/users/#{@user.id}/avatar")
    expect(res['content-type']).to eq 'image/png'
    expect(res.body.b).to eq FAKE_AVATAR_PNG
    expect(http_get("/avatars/by-email/#{email_hash(@user.email_address)}").body.b).to eq FAKE_AVATAR_PNG
  end

  scenario 'the UI shows avatars with the name in the tooltip' do
    visit '/'
    expect(page).to have_css ".user-uid img.avatar[alt='#{@admin.login}']"
    find('.user-uid img.avatar').hover
    expect(page).to have_css '.tooltip', text: @admin.name
    expect(page).to have_css '.tooltip', text: "@#{@admin.login}"
    expect(page).to have_css '.tooltip', text: 'Admin'

    visit '/users/'
    within(:css, 'tr.user', text: @user.login) do
      img = find("img.avatar[alt='#{@user.login}']")
      expect(img[:src]).to end_with "/users/#{@user.id}/avatar"
    end
    expect(page).not_to have_content @user.name
    expect_avatars_loaded(3) # navbar, admin, user

    visit "/users/#{@user.id}"
    expect(page).to have_css "h2 img.avatar[alt='#{@user.login}']"
    expect_avatars_loaded(3) # navbar, center nav, heading
  end

  context 'with commits' do
    before :each do
      database[:repositories].insert(id: 'avatars-project', name: 'Avatars', git_url: 'local')
      @commit_id = 'c' * 40
      database[:commits].insert(id: @commit_id, tree_id: 't' * 40, subject: 'Avatar commit',
                                author_name: 'Octo Cat', author_email: @user.email_address.upcase,
                                committer_name: 'Someone Else', committer_email: 'else@example.org',
                                author_date: Time.now, committer_date: Time.now)
      database[:branches].insert(name: 'main', repository_id: 'avatars-project', current_commit_id: @commit_id)
    end

    scenario 'authors and committers are shown as avatars, by email address' do
      visit '/commits/'
      within(:css, 'tr', text: 'Avatar commit') do
        img = find("td.author img.avatar[alt='Octo Cat']")
        expect(img[:src]).to end_with "/avatars/by-email/#{email_hash(@user.email_address)}"
        expect(page).not_to have_content 'Octo Cat'
      end

      visit "/projects/avatars-project/commits/#{@commit_id}"
      expect(page).to have_css "img.avatar[alt='Octo Cat'][src$='#{email_hash(@user.email_address)}']"
      expect(page).to have_css "img.avatar[alt='Someone Else'][src$='#{email_hash('else@example.org')}']"
      expect(page).to have_content "<#{@user.email_address.upcase}>"
      expect(page).not_to have_content 'Someone Else'
      expect_avatars_loaded(3) # navbar, author, committer
      find("img.avatar[alt='Someone Else']").hover
      expect(page).to have_css '.tooltip', text: 'Someone Else'
      expect(page).to have_css '.tooltip', text: 'else@example.org'
    end
  end
end
