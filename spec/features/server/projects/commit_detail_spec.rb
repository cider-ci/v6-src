require 'spec_helper'

feature 'Commit detail page' do

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @project_id = 'demo-repo'
    database[:repositories].insert(
      id: @project_id,
      name: 'Demo Repository',
      git_url: 'https://example.test/demo.git'
    )
  end

  def insert_commit(id, opts = {})
    database[:commits].insert({
      id: id,
      tree_id: 'b' * 40,
      depth: 0,
      author_name: 'Alice',
      author_email: 'alice@example.test',
      committer_name: 'Alice',
      committer_email: 'alice@example.test',
      subject: 'Add greeting',
      body:    "Add a friendly greeting\n\nWith a detailed body.\n"
    }.merge(opts))
  end

  scenario 'unsigned commit shows unsigned panel' do
    cid = 'c' * 40
    insert_commit(cid)

    visit "/projects/#{@project_id}/commits/#{cid}"

    expect(page).to have_content 'Add greeting'
    expect(page).to have_content cid
    expect(page).to have_content 'Unsigned commit'
    expect(page).to have_content 'alice@example.test'
  end

  scenario 'signed but untrusted commit shows untrusted panel' do
    cid = 'd' * 40
    insert_commit(cid,
                  signature: '-----BEGIN PGP SIGNATURE-----\nfake\n-----END PGP SIGNATURE-----',
                  signed_message: 'message')

    visit "/projects/#{@project_id}/commits/#{cid}"
    expect(page).to have_content 'Signed, but the signing key is not trusted'
  end

  scenario 'signed and trusted commit shows trusted key info' do
    cid = 'e' * 40
    fp  = 'ABCDEF0123456789ABCDEF0123456789ABCDEF01'
    insert_commit(cid,
                  signature: '-----BEGIN PGP SIGNATURE-----\nfake\n-----END PGP SIGNATURE-----',
                  signed_message: 'message',
                  signature_fingerprint: fp)
    database[:gpg_keys].insert(
      fingerprint: fp,
      name: 'Release signing key',
      ascii_key: 'fake-ascii-key',
      user_id: nil
    )

    visit "/projects/#{@project_id}/commits/#{cid}"

    expect(page).to have_content 'Signed by'
    expect(page).to have_content 'Release signing key'
    expect(page).to have_content fp
  end

  scenario 'commit with parents links to parent commits' do
    parent = 'f' * 40
    child  = '1' * 40
    insert_commit(parent)
    insert_commit(child)
    database[:commit_arcs].insert(parent_id: parent, child_id: child)

    visit "/projects/#{@project_id}/commits/#{child}"
    expect(page).to have_content parent[0, 8]
  end

  scenario 'unknown commit id returns 404' do
    visit "/projects/#{@project_id}/commits/0000000000000000000000000000000000000000"
    expect(page).to have_content 'Request ERROR 404'
  end

end
