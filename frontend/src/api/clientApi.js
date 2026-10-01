const BASE_URL = import.meta.env.VITE_API_URL

export async function getClients(){
    const response = await fetch(`${BASE_URL}/client`)

    if(!response.ok){
        throw new Error('Failed to retrieve clients')
    }

    return response.json()
}

export async function createClient(client){
    const response = await fetch(`${BASE_URL}/client`, {
        method: 'POST',

        headers:{
            'Content-Type': 'application/json'
        },

        body: JSON.stringify(client)
    })

    if(!response.ok){
        throw new Error('Failed to create client')
    }

    return response.json()
}

export async function updateClient(clientId, client){
    const response = await fetch(`${BASE_URL}/client/${clientId}`,
        {
            method: 'PUT',
            headers:{
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(client)
        }
    )

    if(!response.ok){
        throw new Error('Failed to update client')
    }

    return response.json()
}

export async function deleteClient(clientId){
    const response = await fetch(`${BASE_URL}/client/${clientId}`,
        {
            method: 'DELETE'
        }
    )

    if(!response.ok){
        throw new Error('Failed to delete client')
    }

    return response.json()
}